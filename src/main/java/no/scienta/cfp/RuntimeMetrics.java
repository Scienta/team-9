package no.scienta.cfp;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

/**
 * Logs what the container gives us and what we use, to tell CPU throttling from pool waits and GC/JIT when requests
 * get slow (timeouts after deploys, ticks 134-160). Log only: nothing in the API changes.
 *
 * <ul>
 *   <li>At start, one {@code runtime:} line: the cgroup CPU quota ({@code cpu.max}), the processors the JVM sees,
 *       the GC and the max heap.</li>
 *   <li>Every {@code cfp.metrics.interval-seconds} (10), one {@code metrics:} line with what changed since the last:
 *       CPU used, cgroup throttling (periods and time), GC and JIT time, the pool's busiest moment (sampled every
 *       second) and the requests' count and latency percentiles. Quiet intervals (no requests, no throttling) are
 *       skipped.</li>
 * </ul>
 *
 * Every read is best effort: off Linux (tests on a laptop) the cgroup fields are just missing.
 */
@Component
public class RuntimeMetrics implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger("metrics");
    private static final Path CGROUP = Paths.get("/sys/fs/cgroup");
    private static final int MAX_SAMPLES = 20_000;

    private final DataSource dataSource;
    private final int intervalSeconds;

    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    // Latencies of the requests in the current interval (ms). Guarded by this.
    private long[] latencies = new long[1024];
    private int latencyCount;

    // Pool peaks in the current interval, from the 1 s sampler. Guarded by this.
    private int maxActive;
    private int maxWaiting;

    // Counters at the previous report.
    private Map<String, Long> lastCpuStat = Map.of();
    private long lastProcessCpuNanos = -1;
    private long lastGcMillis;
    private long lastGcCount;
    private long lastJitMillis;
    private long lastReportNanos;

    public RuntimeMetrics(DataSource dataSource, @Value("${cfp.metrics.interval-seconds:10}") int intervalSeconds) {
        this.dataSource = dataSource;
        this.intervalSeconds = intervalSeconds;
    }

    /** Called by {@link RequestLoggingFilter} once per request. */
    public synchronized void recordRequest(long millis) {
        if (latencyCount == latencies.length) {
            if (latencies.length >= MAX_SAMPLES) {
                return; // a flood: the percentiles of the first 20k are good enough
            }
            latencies = Arrays.copyOf(latencies, latencies.length * 2);
        }
        latencies[latencyCount++] = millis;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void start() {
        running = true;
        try {
            log.info("runtime: " + format(staticFacts()));
        } catch (Throwable e) {
            log.warn("runtime facts unavailable: " + e);
        }
        synchronized (this) {
            latencyCount = 0; // the warm-up's requests ran before this; they're in its own log line
        }
        snapshotCounters();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "runtime-metrics");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::samplePool, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::reportSafely, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ---------------------------------------------------------------- what we're given

    Map<String, Object> staticFacts() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpu.max", readCgroup("cpu.max"));          // "<quota> <period>" in µs, or "max <period>"
        m.put("cpus", quotaCpus(readCgroup("cpu.max")));  // quota / period, e.g. 0.5
        m.put("cpu.weight", readCgroup("cpu.weight"));
        m.put("memory.max", readCgroup("memory.max"));
        m.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        m.put("gc", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).collect(Collectors.joining("+")));
        m.put("heapMaxMb", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() / (1024 * 1024));
        CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
        m.put("jit", jit == null ? null : jit.getName());
        m.put("jvmArgs", String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()));
        HikariDataSource hikari = hikari();
        m.put("poolMax", hikari == null ? null : hikari.getMaximumPoolSize());
        return m;
    }

    static String quotaCpus(String cpuMax) {
        if (cpuMax == null) {
            return null;
        }
        String[] parts = cpuMax.trim().split("\\s+");
        if (parts.length != 2 || parts[0].equals("max")) {
            return parts[0];
        }
        try {
            return String.format("%.3f", Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- what we use

    private void samplePool() {
        try {
            HikariDataSource hikari = hikari();
            HikariPoolMXBean pool = hikari == null ? null : hikari.getHikariPoolMXBean();
            if (pool == null) {
                return;
            }
            int active = pool.getActiveConnections();
            int waiting = pool.getThreadsAwaitingConnection();
            synchronized (this) {
                maxActive = Math.max(maxActive, active);
                maxWaiting = Math.max(maxWaiting, waiting);
            }
        } catch (Throwable ignored) {
            // the pool can be closing during shutdown
        }
    }

    private void reportSafely() {
        try {
            String line = report();
            if (line != null) {
                log.info("metrics: " + line);
            }
        } catch (Throwable e) {
            log.warn("metrics failed: " + e);
        }
    }

    /** Builds the interval's line and resets the interval, or returns null for a quiet interval. */
    String report() {
        long[] lat;
        int active;
        int waiting;
        synchronized (this) {
            lat = Arrays.copyOf(latencies, latencyCount);
            latencyCount = 0;
            active = maxActive;
            waiting = maxWaiting;
            maxActive = 0;
            maxWaiting = 0;
        }
        long now = System.nanoTime();
        double seconds = (now - lastReportNanos) / 1e9;

        Map<String, Long> cpuStat = cpuStat();
        long processCpu = processCpuNanos();
        long[] gc = gc();
        long jit = jitMillis();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", String.format("%.1f", seconds));
        m.put("reqs", lat.length);
        if (lat.length > 0) {
            Arrays.sort(lat);
            m.put("p50", lat[percentileIndex(lat.length, 50)]);
            m.put("p95", lat[percentileIndex(lat.length, 95)]);
            m.put("max", lat[lat.length - 1]);
        }
        if (processCpu >= 0 && lastProcessCpuNanos >= 0 && seconds > 0) {
            // CPU seconds per wall second: 0.50 means half a CPU on average.
            m.put("cpu", String.format("%.2f", (processCpu - lastProcessCpuNanos) / 1e9 / seconds));
        }
        long throttled = delta(cpuStat, "nr_throttled");
        if (cpuStat.containsKey("nr_periods")) {
            m.put("periods", delta(cpuStat, "nr_periods"));
            m.put("throttled", throttled);
            m.put("throttledMs", delta(cpuStat, "throttled_usec") / 1000);
            m.put("cgroupCpuMs", delta(cpuStat, "usage_usec") / 1000);
        }
        m.put("gcMs", gc[1] - lastGcMillis);
        m.put("gcs", gc[0] - lastGcCount);
        m.put("jitMs", jit - lastJitMillis);
        m.put("poolActiveMax", active);
        m.put("poolWaitingMax", waiting);
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        m.put("heapMb", heap.getUsed() / (1024 * 1024));
        m.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());

        lastCpuStat = cpuStat;
        lastProcessCpuNanos = processCpu;
        lastGcCount = gc[0];
        lastGcMillis = gc[1];
        lastJitMillis = jit;
        lastReportNanos = now;

        boolean quiet = lat.length == 0 && throttled == 0 && waiting == 0;
        return quiet ? null : format(m);
    }

    static int percentileIndex(int n, int p) {
        return Math.min(n - 1, Math.max(0, (int) Math.ceil(n * p / 100.0) - 1));
    }

    private void snapshotCounters() {
        lastCpuStat = cpuStat();
        lastProcessCpuNanos = processCpuNanos();
        long[] gc = gc();
        lastGcCount = gc[0];
        lastGcMillis = gc[1];
        lastJitMillis = jitMillis();
        lastReportNanos = System.nanoTime();
    }

    private long delta(Map<String, Long> now, String key) {
        Long a = now.get(key);
        Long b = lastCpuStat.get(key);
        return a == null || b == null ? 0 : a - b;
    }

    // ---------------------------------------------------------------- sources

    private static String readCgroup(String file) {
        try {
            return Files.readString(CGROUP.resolve(file)).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** cgroup v2 cpu.stat: usage_usec, nr_periods, nr_throttled, throttled_usec, ... Empty off Linux. */
    private static Map<String, Long> cpuStat() {
        String s = readCgroup("cpu.stat");
        if (s == null) {
            return Map.of();
        }
        Map<String, Long> m = new LinkedHashMap<>();
        for (String line : s.split("\n")) {
            String[] kv = line.trim().split("\\s+");
            if (kv.length == 2) {
                try {
                    m.put(kv[0], Long.parseLong(kv[1]));
                } catch (NumberFormatException ignored) {
                    // not a counter
                }
            }
        }
        return m;
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
        }
        return -1;
    }

    /** {count, millis} over all collectors. */
    private static long[] gc() {
        long count = 0;
        long millis = 0;
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean b : beans) {
            count += Math.max(0, b.getCollectionCount());
            millis += Math.max(0, b.getCollectionTime());
        }
        return new long[] {count, millis};
    }

    private static long jitMillis() {
        CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
        return jit != null && jit.isCompilationTimeMonitoringSupported() ? jit.getTotalCompilationTime() : 0;
    }

    private HikariDataSource hikari() {
        return dataSource instanceof HikariDataSource ? (HikariDataSource) dataSource : null;
    }

    private static String format(Map<String, Object> m) {
        return m.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(" "));
    }
}
