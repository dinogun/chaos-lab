package ai.causa.libertyperf.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a customer account (bank account or airline loyalty account).
 */
public class Account {

    public enum AccountType { CHECKING, SAVINGS, CREDIT, FREQUENT_FLYER }

    private String      accountId;
    private String      ownerId;
    private String      ownerName;
    private AccountType accountType;
    private BigDecimal  balance;
    private String      currency;
    private boolean     active;
    private Instant     createdAt;
    private long        transactionCount;

    public Account() {}

    // ---- getters / setters ----

    public String getAccountId()                  { return accountId; }
    public void setAccountId(String v)            { this.accountId = v; }

    public String getOwnerId()                    { return ownerId; }
    public void setOwnerId(String v)              { this.ownerId = v; }

    public String getOwnerName()                  { return ownerName; }
    public void setOwnerName(String v)            { this.ownerName = v; }

    public AccountType getAccountType()           { return accountType; }
    public void setAccountType(AccountType v)     { this.accountType = v; }

    public BigDecimal getBalance()                { return balance; }
    public void setBalance(BigDecimal v)          { this.balance = v; }

    public String getCurrency()                   { return currency; }
    public void setCurrency(String v)             { this.currency = v; }

    public boolean isActive()                     { return active; }
    public void setActive(boolean v)              { this.active = v; }

    public Instant getCreatedAt()                 { return createdAt; }
    public void setCreatedAt(Instant v)           { this.createdAt = v; }

    public long getTransactionCount()             { return transactionCount; }
    public void setTransactionCount(long v)       { this.transactionCount = v; }
}
