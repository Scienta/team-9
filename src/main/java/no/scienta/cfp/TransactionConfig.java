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

/**
 * Sets each transaction's isolation level with {@code SET TRANSACTION ISOLATION LEVEL ...} as the transaction's first
 * statement. The JDBC driver's {@code setTransactionIsolation} is a session-level SET, and in production it doesn't reach
 * the transaction: the database sits behind a connection pooler and starts transactions at REPEATABLE READ. A
 * lock-then-read then saw the snapshot from before the lock wait, and every lock-guarded limit broke under load (21 of
 * 20 workshop seats, one speaker twice in a slot; lessons.md L18). A statement inside the transaction travels with it.
 *
 * {@code @Transactional} without an isolation gets READ COMMITTED, the level every lock-then-read here is written for.
 * JPA isn't used, so this replaces the JPA transaction manager Boot would otherwise configure.
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

        @Override
        protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition) throws SQLException {
            super.prepareTransactionalConnection(con, definition);
            try (Statement s = con.createStatement()) {
                s.execute("SET TRANSACTION ISOLATION LEVEL " + level(definition.getIsolationLevel()));
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
