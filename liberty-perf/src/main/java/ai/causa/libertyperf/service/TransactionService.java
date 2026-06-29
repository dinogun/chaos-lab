package ai.causa.libertyperf.service;

import ai.causa.libertyperf.model.Account;
import ai.causa.libertyperf.model.Transaction;
import ai.causa.libertyperf.repository.AccountRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Timed;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Business logic for account and transaction operations.
 *
 * <p><strong>Primary chaos scenario — HTTP large-response + keep-alive:</strong><br>
 * See {@link ai.causa.libertyperf.service.ResponsePaddingService}. Activated via
 * {@code CHAOS_HTTP_LARGE_RESPONSE_ENABLED=true}. This reproduces the RCA scenario
 * where Liberty HTTP misconfiguration (large responses held on keep-alive connections)
 * causes heap pressure proportional to concurrent connection count.
 *
 * <p><strong>Secondary chaos knob — background heap leak:</strong><br>
 * When {@code chaos.memory.response.cache.enabled=true} each transaction response is
 * additionally cached in a static map that is never evicted, growing heap indefinitely
 * regardless of HTTP traffic. Use this to stack on top of the HTTP scenario or to
 * accelerate OOM in isolation.
 */
@ApplicationScoped
public class TransactionService {

    private static final Logger LOG = Logger.getLogger(TransactionService.class.getName());

    @Inject
    AccountRepository accountRepository;

    /**
     * When true, responses are cached in an unbounded map — simulates a memory leak
     * caused by a forgotten cache eviction policy.
     */
    @ConfigProperty(name = "chaos.memory.response.cache.enabled", defaultValue = "false")
    boolean responseCacheEnabled;

    /**
     * Number of duplicate large objects to allocate per transaction request when cache is enabled.
     * Increasing this value accelerates heap exhaustion.
     */
    @ConfigProperty(name = "chaos.memory.objects.per.tx", defaultValue = "1")
    int objectsPerTx;

    /** Static map that is never cleared when chaos cache is on. */
    private static final Map<String, List<byte[]>> LEAK_CACHE = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Account operations
    // -------------------------------------------------------------------------

    @Timed(name = "account.lookup.time", unit = MetricUnits.MILLISECONDS,
            description = "Time to look up an account by ID")
    public Optional<Account> getAccount(String accountId) {
        LOG.fine("Looking up account: " + accountId);
        return accountRepository.findById(accountId);
    }

    @Timed(name = "account.list.time", unit = MetricUnits.MILLISECONDS,
            description = "Time to list all accounts")
    public List<Account> listAccounts() {
        LOG.fine("Listing all accounts");
        return accountRepository.findAll();
    }

    @Counted(name = "accounts.created", description = "Total accounts created")
    @Timed(name = "account.create.time", unit = MetricUnits.MILLISECONDS,
            description = "Time to create a new account")
    public Account createAccount(String ownerName, Account.AccountType accountType, BigDecimal initialBalance) {
        Account a = new Account();
        a.setAccountId(UUID.randomUUID().toString());
        a.setOwnerId(UUID.randomUUID().toString());
        a.setOwnerName(ownerName);
        a.setAccountType(accountType);
        a.setBalance(initialBalance != null ? initialBalance : BigDecimal.ZERO);
        a.setCurrency("USD");
        a.setActive(true);
        a.setCreatedAt(java.time.Instant.now());
        a.setTransactionCount(0);
        return accountRepository.save(a);
    }

    // -------------------------------------------------------------------------
    // Transaction operations
    // -------------------------------------------------------------------------

    @Counted(name = "transactions.submitted", description = "Total transactions submitted")
    @Timed(name = "transaction.process.time", unit = MetricUnits.MILLISECONDS,
            description = "End-to-end transaction processing time")
    public Transaction processTransaction(String accountId, Transaction.Type type,
                                          BigDecimal amount, String currency,
                                          String description) {

        String correlationId = UUID.randomUUID().toString();
        LOG.info(String.format("[%s] Processing %s transaction for account %s amount=%s %s",
                correlationId, type, accountId, amount, currency));

        Optional<Account> maybeAccount = accountRepository.findById(accountId);
        if (maybeAccount.isEmpty()) {
            LOG.warning(String.format("[%s] Account not found: %s", correlationId, accountId));
            throw new IllegalArgumentException("Account not found: " + accountId);
        }

        Account account = maybeAccount.get();
        BigDecimal newBalance = computeNewBalance(account.getBalance(), type, amount);

        Transaction tx = Transaction.newTransaction(accountId, type, amount, currency, description);
        tx.setCorrelationId(correlationId);

        // Persist transaction and update account balance
        accountRepository.saveTransaction(tx);
        accountRepository.updateBalance(accountId, newBalance);

        tx.setStatus(Transaction.Status.COMPLETED);
        tx.setCompletedAt(Instant.now());

        LOG.info(String.format("[%s] Transaction %s COMPLETED. New balance: %s",
                correlationId, tx.getTransactionId(), newBalance));

        // Chaos: optionally leak memory per transaction
        if (responseCacheEnabled) {
            simulateMemoryLeak(correlationId);
        }

        return tx;
    }

    @Timed(name = "transaction.history.time", unit = MetricUnits.MILLISECONDS,
            description = "Time to retrieve transaction history")
    public List<Transaction> getTransactionHistory(String accountId, int limit) {
        LOG.fine("Retrieving transaction history for " + accountId);
        return accountRepository.findTransactionsByAccount(accountId, limit);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BigDecimal computeNewBalance(BigDecimal current, Transaction.Type type, BigDecimal amount) {
        return switch (type) {
            case CREDIT   -> current.add(amount);
            case DEBIT    -> current.subtract(amount);
            case TRANSFER -> current.subtract(amount);
        };
    }

    /**
     * Allocates byte arrays that are stored in {@link #LEAK_CACHE} and never released.
     * Each call can grow the heap by {@code objectsPerTx * 64KB}.
     */
    private void simulateMemoryLeak(String correlationId) {
        int objectSize = 64 * 1024; // 64 KB per object
        List<byte[]> blobs = new ArrayList<>(objectsPerTx);
        for (int i = 0; i < objectsPerTx; i++) {
            byte[] blob = new byte[objectSize];
            Arrays.fill(blob, (byte) 'X');
            blobs.add(blob);
        }
        LEAK_CACHE.put(correlationId, blobs);
        LOG.warning(String.format("[CHAOS] Memory leak: cached %d object(s) (%d KB) for correlationId=%s. " +
                "Leak cache size: %d entries. Approx heap allocated: %d KB. " +
                "Set chaos.memory.response.cache.enabled=false to stop leaking.",
                objectsPerTx, (objectsPerTx * objectSize) / 1024, correlationId,
                LEAK_CACHE.size(), (LEAK_CACHE.size() * objectsPerTx * objectSize) / 1024));
    }

    /** Returns current leak cache size for metrics/health exposure. */
    public int getLeakCacheSize() {
        return LEAK_CACHE.size();
    }

    /** Returns approximate heap held by the leak cache in bytes. */
    public long getLeakCacheBytes() {
        return (long) LEAK_CACHE.size() * objectsPerTx * 64 * 1024;
    }
}
