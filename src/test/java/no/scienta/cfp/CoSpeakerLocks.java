package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

/** Lock-contention helpers shared by the co-speaker integration tests. */
final class CoSpeakerLocks {

    private CoSpeakerLocks() {
    }

    /** Opens a transaction on {@code holder} that holds the talk row lock until commit/rollback. */
    static void lockTalk(Connection holder, long talk) throws SQLException {
        holder.setAutoCommit(false);
        try (PreparedStatement s = holder.prepareStatement("SELECT id FROM talk WHERE id = ? FOR UPDATE")) {
            s.setLong(1, talk);
            try (ResultSet rs = s.executeQuery()) {
                assertEquals(true, rs.next(), "talk " + talk + " exists");
            }
        }
    }

    /**
     * Polls pg_stat_activity from an autocommit {@code observer} (inside a transaction the view is a frozen
     * snapshot) until {@code expected} other backends of this database wait on a Lock in the talk-row
     * SELECT ... FOR UPDATE. Gives up well before the app's 2 s lock_timeout.
     */
    static int awaitTalkLockWaiters(Connection observer, int expected) throws Exception {
        long deadline = System.nanoTime() + 1_500_000_000L;
        int waiting;
        do {
            try (Statement s = observer.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                         + " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'"
                         + " AND query LIKE 'SELECT id FROM talk WHERE id = % FOR UPDATE'")) {
                rs.next();
                waiting = rs.getInt(1);
            }
            if (waiting >= expected) {
                return waiting;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return waiting;
    }

    /**
     * Borrows every pooled connection at once (more borrowers than the pool size, each holding its connection
     * for a while) and checks, outside any transaction, that none carries a leaked lock_timeout or isolation.
     */
    static void assertPoolDefaults(DataSource dataSource, String expectedIsolation) throws Exception {
        int poolSize = ((HikariDataSource) dataSource).getMaximumPoolSize();
        assertEquals(10, poolSize, "pool size as configured in application.properties");
        int borrowers = poolSize + 3;
        ExecutorService pool = Executors.newFixedThreadPool(borrowers);
        try {
            List<Future<String[]>> results = new ArrayList<>();
            for (int i = 0; i < borrowers; i++) {
                results.add(pool.submit(() -> {
                    try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                        assertEquals(true, c.getAutoCommit(), "pooled connection handed out in autocommit");
                        String[] row = new String[3];
                        try (ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
                            rs.next();
                            row[0] = rs.getString(1);
                        }
                        try (ResultSet rs = s.executeQuery("SHOW lock_timeout")) {
                            rs.next();
                            row[1] = rs.getString(1);
                        }
                        try (ResultSet rs = s.executeQuery("SHOW transaction_isolation")) {
                            rs.next();
                            row[2] = rs.getString(1);
                        }
                        Thread.sleep(300); // keep it borrowed so the others must take different connections
                        return row;
                    }
                }));
            }
            Set<String> backends = new HashSet<>();
            for (Future<String[]> f : results) {
                String[] row = f.get();
                backends.add(row[0]);
                assertEquals("0", row[1], "lock_timeout leaked onto pooled backend " + row[0]);
                assertEquals(expectedIsolation, row[2], "isolation leaked onto pooled backend " + row[0]);
            }
            assertEquals(poolSize, backends.size(), "every pooled connection was checked");
        } finally {
            pool.shutdownNow();
        }
    }
}
