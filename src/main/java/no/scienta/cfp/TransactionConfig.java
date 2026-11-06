package no.scienta.cfp;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DelegatingTransactionDefinition;

/**
 * Sets each transaction's isolation level with {@code SET TRANSACTION ISOLATION LEVEL ...} as the transaction's first
 * statement. The JDBC driver's {@code setTransactionIsolation} is a session-level SET, and in production it doesn't reach
 * the transaction: the database sits behind a connection pooler and starts transactions at REPEATABLE READ. A
 * lock-then-read then saw the snapshot from before the lock wait, and every lock-guarded limit broke under load (21 of
 * 20 workshop seats, one speaker twice in a slot; lessons.md L18). A statement inside the transaction travels with it.
 *
 * {@code @Transactional} without an isolation gets READ COMMITTED, the level every lock-then-read here is written for.
 * The driver is never asked to set the level itself: its session-level SET (and Spring's and Hikari's resets after
 * commit) would run outside the transaction and, through the pooler, on another client's backend (AGENTS.md).
 * This replaces the JdbcTransactionManager Boot would otherwise configure (no JPA: it's commented out in pom.xml); no
 * {@code spring.transaction.*} properties are set, so Boot's transaction customizers have nothing to apply.
 */
@Configuration
public class TransactionConfig {

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new IsolationInTransactionManager(dataSource);
    }

    static class IsolationInTransactionManager extends JdbcTransactionManager {

        IsolationInTransactionManager(DataSource dataSource) {
            super(dataSource);
        }

        // Spring would call the driver's setTransactionIsolation for any level but DEFAULT: begin with DEFAULT, and
        // keep the requested level for the statement inside the transaction.
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            super.doBegin(transaction, new RequestedIsolation(definition));
        }

        @Override
        protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition) throws SQLException {
            super.prepareTransactionalConnection(con, definition);
            int requested = definition instanceof RequestedIsolation
                    ? ((RequestedIsolation) definition).requested : definition.getIsolationLevel();
            try (Statement s = con.createStatement()) {
                s.execute("SET TRANSACTION ISOLATION LEVEL " + level(requested));
            }
        }

        /** The definition as given, but with DEFAULT isolation, remembering the level that was asked for. */
        static final class RequestedIsolation extends DelegatingTransactionDefinition {
            final int requested;

            RequestedIsolation(TransactionDefinition target) {
                super(target);
                this.requested = target.getIsolationLevel();
            }

            @Override
            public int getIsolationLevel() {
                return ISOLATION_DEFAULT;
            }
        }

        static String level(int isolation) {
            switch (isolation) {
                case TransactionDefinition.ISOLATION_REPEATABLE_READ:
                    return "REPEATABLE READ";
                case TransactionDefinition.ISOLATION_SERIALIZABLE:
                    return "SERIALIZABLE";
                case TransactionDefinition.ISOLATION_READ_UNCOMMITTED:
                    return "READ UNCOMMITTED";
                default: // ISOLATION_DEFAULT and ISOLATION_READ_COMMITTED
                    return "READ COMMITTED";
            }
        }
    }
}
