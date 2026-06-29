package ai.causa.libertyperf.repository;

import ai.causa.libertyperf.model.Account;
import ai.causa.libertyperf.model.Transaction;
import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Repository for Accounts and Transactions backed by JDBC.
 *
 * <p><strong>Chaos knobs:</strong>
 * <ul>
 *   <li>{@code DB_LEAK_CONNECTIONS_ENABLED=true} — acquired connections are never returned to the
 *       pool, simulating a connection leak. Set {@code DB_MAX_POOL_SIZE} low to observe thread
 *       starvation quickly.</li>
 *   <li>{@code DB_SLOW_QUERY_MS} — adds an artificial sleep on every query to mimic a slow
 *       backend, forcing threads to hold connections longer.</li>
 * </ul>
 */
@ApplicationScoped
public class AccountRepository {

    private static final Logger LOG = Logger.getLogger(AccountRepository.class.getName());

    @Resource(lookup = "jdbc/libertyPerfDS")
    DataSource dataSource;

    /**
     * Whether to intentionally leak connections (chaos mode).
     * Default: false (well-behaved).
     */
    @ConfigProperty(name = "chaos.db.leak.enabled", defaultValue = "false")
    boolean leakConnections;

    /**
     * Artificial delay (ms) added to every query to simulate slow backend.
     * Default: 0 (no delay).
     */
    @ConfigProperty(name = "chaos.db.slow.query.ms", defaultValue = "0")
    long slowQueryMs;

    // In-memory seed data for accounts (populated on first use)
    private static final Map<String, Account> SEED_ACCOUNTS = seedAccounts();

    // -------------------------------------------------------------------------
    // Schema bootstrap
    // -------------------------------------------------------------------------

    public void ensureSchema() {
        try (Connection c = dataSource.getConnection();
             Statement s  = c.createStatement()) {

            s.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        account_id        VARCHAR(36)    NOT NULL PRIMARY KEY,
                        owner_id          VARCHAR(36)    NOT NULL,
                        owner_name        VARCHAR(255)   NOT NULL,
                        account_type      VARCHAR(32)    NOT NULL,
                        balance           DECIMAL(15, 2) NOT NULL DEFAULT 0,
                        currency          VARCHAR(3)     NOT NULL DEFAULT 'USD',
                        active            BOOLEAN        NOT NULL DEFAULT TRUE,
                        created_at        TIMESTAMP      NOT NULL,
                        transaction_count BIGINT         NOT NULL DEFAULT 0
                    )""");

            s.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        transaction_id  VARCHAR(36)    NOT NULL PRIMARY KEY,
                        account_id      VARCHAR(36)    NOT NULL,
                        type            VARCHAR(16)    NOT NULL,
                        amount          DECIMAL(15, 2) NOT NULL,
                        currency        VARCHAR(3)     NOT NULL,
                        status          VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
                        created_at      TIMESTAMP      NOT NULL,
                        completed_at    TIMESTAMP,
                        description     VARCHAR(512),
                        correlation_id  VARCHAR(36)
                    )""");

            // Seed accounts if table is empty
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM accounts");
            rs.next();
            if (rs.getInt(1) == 0) {
                insertSeedAccounts(c);
            }

            LOG.info("Database schema initialised successfully");

        } catch (SQLException e) {
            LOG.severe("Schema initialisation failed: " + e.getMessage());
            throw new RuntimeException("Schema init failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Account operations
    // -------------------------------------------------------------------------

    public Optional<Account> findById(String accountId) {
        simulateSlowQuery();
        Connection conn = acquireConnection();
        try {
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM accounts WHERE account_id = ?");
            ps.setString(1, accountId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapAccount(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            LOG.warning("findById failed for " + accountId + ": " + e.getMessage());
            return Optional.empty();
        } finally {
            maybeClose(conn);
        }
    }

    public List<Account> findAll() {
        simulateSlowQuery();
        Connection conn = acquireConnection();
        List<Account> result = new ArrayList<>();
        try {
            Statement s  = conn.createStatement();
            ResultSet rs = s.executeQuery("SELECT * FROM accounts WHERE active = TRUE LIMIT 100");
            while (rs.next()) {
                result.add(mapAccount(rs));
            }
        } catch (SQLException e) {
            LOG.warning("findAll failed: " + e.getMessage());
        } finally {
            maybeClose(conn);
        }
        return result;
    }

    public Account save(Account account) {
        simulateSlowQuery();
        Connection conn = acquireConnection();
        try {
            PreparedStatement ps = conn.prepareStatement("""
                    MERGE INTO accounts
                    (account_id, owner_id, owner_name, account_type, balance, currency,
                     active, created_at, transaction_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setString(1, account.getAccountId());
            ps.setString(2, account.getOwnerId());
            ps.setString(3, account.getOwnerName());
            ps.setString(4, account.getAccountType().name());
            ps.setBigDecimal(5, account.getBalance());
            ps.setString(6, account.getCurrency());
            ps.setBoolean(7, account.isActive());
            ps.setTimestamp(8, Timestamp.from(account.getCreatedAt()));
            ps.setLong(9, account.getTransactionCount());
            ps.executeUpdate();
            return account;
        } catch (SQLException e) {
            LOG.warning("save account failed: " + e.getMessage());
            throw new RuntimeException(e);
        } finally {
            maybeClose(conn);
        }
    }

    public void updateBalance(String accountId, BigDecimal newBalance) {
        simulateSlowQuery();
        Connection conn = acquireConnection();
        try {
            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE accounts SET balance = ?, transaction_count = transaction_count + 1 WHERE account_id = ?");
            ps.setBigDecimal(1, newBalance);
            ps.setString(2, accountId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("updateBalance failed: " + e.getMessage());
            throw new RuntimeException(e);
        } finally {
            maybeClose(conn);
        }
    }

    // -------------------------------------------------------------------------
    // Transaction operations
    // -------------------------------------------------------------------------

    public Transaction saveTransaction(Transaction tx) {
        simulateSlowQuery();
        Connection conn = acquireConnection();
        try {
            PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO transactions
                    (transaction_id, account_id, type, amount, currency,
                     status, created_at, completed_at, description, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setString(1, tx.getTransactionId());
            ps.setString(2, tx.getAccountId());
            ps.setString(3, tx.getType().name());
            ps.setBigDecimal(4, tx.getAmount());
            ps.setString(5, tx.getCurrency());
            ps.setString(6, tx.getStatus().name());
            ps.setTimestamp(7, Timestamp.from(tx.getCreatedAt()));
            ps.setTimestamp(8, tx.getCompletedAt() != null ? Timestamp.from(tx.getCompletedAt()) : null);
            ps.setString(9, tx.getDescription());
            ps.setString(10, tx.getCorrelationId());
            ps.executeUpdate();
            return tx;
        } catch (SQLException e) {
            LOG.warning("saveTransaction failed: " + e.getMessage());
            throw new RuntimeException(e);
        } finally {
            maybeClose(conn);
        }
    }

    public List<Transaction> findTransactionsByAccount(String accountId, int limit) {
        simulateSlowQuery();
        Connection conn = acquireConnection();
        List<Transaction> result = new ArrayList<>();
        try {
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM transactions WHERE account_id = ? ORDER BY created_at DESC LIMIT ?");
            ps.setString(1, accountId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(mapTransaction(rs));
            }
        } catch (SQLException e) {
            LOG.warning("findTransactions failed for " + accountId + ": " + e.getMessage());
        } finally {
            maybeClose(conn);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Acquires a connection from the pool.
     * When {@link #leakConnections} is true the connection is not returned —
     * this is the core chaos mechanism.
     */
    private Connection acquireConnection() {
        try {
            Connection conn = dataSource.getConnection();
            if (leakConnections) {
                LOG.warning("[CHAOS] Connection acquired and intentionally NOT returned to pool — " +
                        "pool will exhaust under sustained load. " +
                        "Set chaos.db.leak.enabled=false to restore normal behaviour.");
            }
            return conn;
        } catch (SQLException e) {
            LOG.severe("[CHAOS] Failed to acquire DB connection — pool may be exhausted: " + e.getMessage());
            throw new RuntimeException("Could not acquire DB connection", e);
        }
    }

    /**
     * Closes the connection only when leak mode is disabled.
     */
    private void maybeClose(Connection conn) {
        if (!leakConnections && conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                LOG.warning("Failed to close connection: " + e.getMessage());
            }
        }
    }

    private void simulateSlowQuery() {
        if (slowQueryMs > 0) {
            try {
                LOG.fine("[CHAOS] Simulating slow query: sleeping " + slowQueryMs + "ms");
                Thread.sleep(slowQueryMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private Account mapAccount(ResultSet rs) throws SQLException {
        Account a = new Account();
        a.setAccountId(rs.getString("account_id"));
        a.setOwnerId(rs.getString("owner_id"));
        a.setOwnerName(rs.getString("owner_name"));
        a.setAccountType(Account.AccountType.valueOf(rs.getString("account_type")));
        a.setBalance(rs.getBigDecimal("balance"));
        a.setCurrency(rs.getString("currency"));
        a.setActive(rs.getBoolean("active"));
        a.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        a.setTransactionCount(rs.getLong("transaction_count"));
        return a;
    }

    private Transaction mapTransaction(ResultSet rs) throws SQLException {
        Transaction t = new Transaction();
        t.setTransactionId(rs.getString("transaction_id"));
        t.setAccountId(rs.getString("account_id"));
        t.setType(Transaction.Type.valueOf(rs.getString("type")));
        t.setAmount(rs.getBigDecimal("amount"));
        t.setCurrency(rs.getString("currency"));
        t.setStatus(Transaction.Status.valueOf(rs.getString("status")));
        t.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        Timestamp comp = rs.getTimestamp("completed_at");
        if (comp != null) t.setCompletedAt(comp.toInstant());
        t.setDescription(rs.getString("description"));
        t.setCorrelationId(rs.getString("correlation_id"));
        return t;
    }

    // -------------------------------------------------------------------------
    // Seed data
    // -------------------------------------------------------------------------

    private void insertSeedAccounts(Connection c) throws SQLException {
        for (Account a : SEED_ACCOUNTS.values()) {
            PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO accounts
                    (account_id, owner_id, owner_name, account_type, balance, currency, active, created_at, transaction_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setString(1, a.getAccountId());
            ps.setString(2, a.getOwnerId());
            ps.setString(3, a.getOwnerName());
            ps.setString(4, a.getAccountType().name());
            ps.setBigDecimal(5, a.getBalance());
            ps.setString(6, a.getCurrency());
            ps.setBoolean(7, true);
            ps.setTimestamp(8, Timestamp.from(a.getCreatedAt()));
            ps.setLong(9, 0L);
            ps.executeUpdate();
        }
        LOG.info("Seeded " + SEED_ACCOUNTS.size() + " accounts into database");
    }

    private static Map<String, Account> seedAccounts() {
        Map<String, Account> map = new LinkedHashMap<>();
        String[][] seeds = {
            {"ACC-001", "USR-001", "Alice Johnson",    "CHECKING"},
            {"ACC-002", "USR-002", "Bob Smith",        "SAVINGS"},
            {"ACC-003", "USR-003", "Carol Williams",   "CREDIT"},
            {"ACC-004", "USR-004", "David Brown",      "CHECKING"},
            {"ACC-005", "USR-005", "Eve Davis",        "FREQUENT_FLYER"},
            {"ACC-006", "USR-006", "Frank Miller",     "SAVINGS"},
            {"ACC-007", "USR-007", "Grace Wilson",     "CHECKING"},
            {"ACC-008", "USR-008", "Henry Moore",      "CREDIT"},
            {"ACC-009", "USR-009", "Iris Taylor",      "FREQUENT_FLYER"},
            {"ACC-010", "USR-010", "Jack Anderson",    "CHECKING"},
        };
        for (String[] row : seeds) {
            Account a = new Account();
            a.setAccountId(row[0]);
            a.setOwnerId(row[1]);
            a.setOwnerName(row[2]);
            a.setAccountType(Account.AccountType.valueOf(row[3]));
            a.setBalance(new BigDecimal("10000.00"));
            a.setCurrency("USD");
            a.setActive(true);
            a.setCreatedAt(Instant.now());
            map.put(row[0], a);
        }
        return Collections.unmodifiableMap(map);
    }
}
