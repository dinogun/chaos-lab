package ai.causa.libertyperf.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a financial transaction (e.g. wire transfer, card payment).
 */
public class Transaction {

    public enum Type { CREDIT, DEBIT, TRANSFER }
    public enum Status { PENDING, COMPLETED, FAILED, TIMEOUT }

    private String  transactionId;
    private String  accountId;
    private Type    type;
    private BigDecimal amount;
    private String  currency;
    private Status  status;
    private Instant createdAt;
    private Instant completedAt;
    private String  description;
    private String  correlationId;

    public Transaction() {}

    public static Transaction newTransaction(String accountId, Type type, BigDecimal amount, String currency, String description) {
        Transaction t = new Transaction();
        t.transactionId  = UUID.randomUUID().toString();
        t.accountId      = accountId;
        t.type           = type;
        t.amount         = amount;
        t.currency       = currency;
        t.status         = Status.PENDING;
        t.createdAt      = Instant.now();
        t.description    = description;
        t.correlationId  = UUID.randomUUID().toString();
        return t;
    }

    // ---- getters / setters ----

    public String getTransactionId()           { return transactionId; }
    public void setTransactionId(String v)     { this.transactionId = v; }

    public String getAccountId()               { return accountId; }
    public void setAccountId(String v)         { this.accountId = v; }

    public Type getType()                      { return type; }
    public void setType(Type v)                { this.type = v; }

    public BigDecimal getAmount()              { return amount; }
    public void setAmount(BigDecimal v)        { this.amount = v; }

    public String getCurrency()                { return currency; }
    public void setCurrency(String v)          { this.currency = v; }

    public Status getStatus()                  { return status; }
    public void setStatus(Status v)            { this.status = v; }

    public Instant getCreatedAt()              { return createdAt; }
    public void setCreatedAt(Instant v)        { this.createdAt = v; }

    public Instant getCompletedAt()            { return completedAt; }
    public void setCompletedAt(Instant v)      { this.completedAt = v; }

    public String getDescription()             { return description; }
    public void setDescription(String v)       { this.description = v; }

    public String getCorrelationId()           { return correlationId; }
    public void setCorrelationId(String v)     { this.correlationId = v; }
}
