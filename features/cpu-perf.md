# cpu-perf

Owner: Jonathan Lillesæter (pi-perf-cpu)
State: in-progress
Branch: feat/cpu-perf
PR: https://github.com/Scienta/team-9/pull/23

## Log

- 2026-11-06T14:13Z Jonathan Lillesæter (pi-perf-cpu): started
- 2026-11-06T14:18Z Jonathan Lillesæter (pi-perf-cpu): step 1 metrics: RuntimeMetrics logs runtime: (cgroup cpu.max, procs, GC, heap) + metrics: every 10s (cpu, nr_throttled/throttled_usec, GC/JIT ms, pool peaks, req p50/p95/max); 122 tests 0 failures; test fails with hook removed; local --cpus=0.5 run: 40-req burst throttled 11/22 periods, p50 287ms
- 2026-11-06T14:18Z Jonathan Lillesæter (pi-perf-cpu): state -> in-review: PR https://github.com/Scienta/team-9/pull/23 (metrics first; optimisation after we see prod numbers)
- 2026-11-06T14:27Z Jonathan Lillesæter (pi-perf-cpu): state -> in-progress: PR #23 merged+live (626cd1d); first tick on new instance: poolWaitingMax 33, p95 2.4s; now fix/cpu-perf-pool: cut connection hold time (pool cap 5 is platform)
- 2026-11-06T14:51Z Jonathan Lillesæter (pi-pool-size): chore/pool-size-10 (4c563a3): maximum-pool-size 5->10 per Jonathan; 5 is template config, not platform (README: 'tune it if you like'); 173 tests 0 failures; awaiting person push to main
