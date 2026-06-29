package ai.causa.libertyperf.rest;

import ai.causa.libertyperf.model.Account;
import ai.causa.libertyperf.model.ApiResponse;
import ai.causa.libertyperf.model.Transaction;
import ai.causa.libertyperf.service.ResponsePaddingService;
import ai.causa.libertyperf.service.TransactionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * REST resource exposing account and transaction operations.
 * Designed to handle high concurrency (100+ concurrent requests).
 */
@Path("/api/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounts & Transactions", description = "Banking-style transactional operations")
public class AccountResource {

    private static final Logger LOG = Logger.getLogger(AccountResource.class.getName());

    @Inject
    TransactionService transactionService;

    @Inject
    ResponsePaddingService paddingService;

    // -------------------------------------------------------------------------
    // Account endpoints
    // -------------------------------------------------------------------------

    @POST
    @Operation(summary = "Create a new account")
    @APIResponse(responseCode = "201", description = "Account created")
    @APIResponse(responseCode = "400", description = "Invalid request")
    public Response createAccount(AccountRequest request) {
        if (request == null || request.getOwnerName() == null || request.getAccountType() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("ownerName and accountType are required",
                            "INVALID_REQUEST", UUID.randomUUID().toString()))
                    .build();
        }
        String correlationId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        Account.AccountType type;
        try {
            type = Account.AccountType.valueOf(request.getAccountType().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Invalid accountType: " + request.getAccountType(),
                            "INVALID_REQUEST", correlationId))
                    .build();
        }
        Account created = transactionService.createAccount(
                request.getOwnerName(), type, request.getInitialBalance());
        ApiResponse<Account> resp = ApiResponse.ok(created, correlationId, System.currentTimeMillis() - start);
        paddingService.pad(resp);
        return Response.status(Response.Status.CREATED)
                .entity(resp)
                .build();
    }

    @GET
    @Operation(summary = "List all accounts", description = "Returns up to 100 active accounts")
    @APIResponse(responseCode = "200", description = "List of accounts")
    public Response listAccounts() {
        String correlationId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        LOG.info("[" + correlationId + "] GET /api/accounts");

        List<Account> accounts = transactionService.listAccounts();

        ApiResponse<List<Account>> resp = ApiResponse.ok(accounts, correlationId, System.currentTimeMillis() - start);
        paddingService.pad(resp);
        return Response.ok(resp).build();
    }

    @GET
    @Path("/{accountId}")
    @Operation(summary = "Get account by ID")
    @APIResponse(responseCode = "200", description = "Account details")
    @APIResponse(responseCode = "404", description = "Account not found")
    public Response getAccount(
            @Parameter(description = "Account identifier") @PathParam("accountId") String accountId) {

        String correlationId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        LOG.info("[" + correlationId + "] GET /api/accounts/" + accountId);

        Optional<Account> account = transactionService.getAccount(accountId);

        if (account.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("Account not found", "NOT_FOUND", correlationId))
                    .build();
        }

        ApiResponse<Account> resp = ApiResponse.ok(account.get(), correlationId, System.currentTimeMillis() - start);
        paddingService.pad(resp);
        return Response.ok(resp).build();
    }

    // -------------------------------------------------------------------------
    // Transaction endpoints
    // -------------------------------------------------------------------------

    @GET
    @Path("/{accountId}/transactions")
    @Operation(summary = "Get transaction history for an account")
    @APIResponse(responseCode = "200", description = "List of transactions")
    public Response getTransactionHistory(
            @PathParam("accountId") String accountId,
            @QueryParam("limit") @DefaultValue("20") int limit) {

        String correlationId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        LOG.info("[" + correlationId + "] GET /api/accounts/" + accountId + "/transactions?limit=" + limit);

        List<Transaction> txList = transactionService.getTransactionHistory(accountId, limit);

        ApiResponse<List<Transaction>> resp = ApiResponse.ok(txList, correlationId, System.currentTimeMillis() - start);
        paddingService.pad(resp);
        return Response.ok(resp).build();
    }

    @POST
    @Path("/{accountId}/transactions")
    @Operation(summary = "Submit a transaction (credit, debit, or transfer)")
    @APIResponse(responseCode = "201", description = "Transaction created and completed")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Account not found")
    public Response submitTransaction(
            @PathParam("accountId") String accountId,
            TransactionRequest request) {

        if (request == null || request.getType() == null || request.getAmount() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Request body is required with type and amount",
                            "INVALID_REQUEST", UUID.randomUUID().toString()))
                    .build();
        }

        String correlationId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        LOG.info(String.format("[%s] POST /api/accounts/%s/transactions type=%s amount=%s",
                correlationId, accountId, request.getType(), request.getAmount()));

        try {
            Transaction tx = transactionService.processTransaction(
                    accountId,
                    Transaction.Type.valueOf(request.getType().toUpperCase()),
                    request.getAmount(),
                    request.getCurrency() != null ? request.getCurrency() : "USD",
                    request.getDescription());

            ApiResponse<Transaction> resp = ApiResponse.ok(tx, correlationId, System.currentTimeMillis() - start);
            paddingService.pad(resp);
            return Response.status(Response.Status.CREATED)
                    .entity(resp)
                    .build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error(e.getMessage(), "NOT_FOUND", correlationId))
                    .build();
        } catch (Exception e) {
            LOG.severe("[" + correlationId + "] Transaction failed: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Transaction processing failed", e.getMessage(), correlationId))
                    .build();
        }
    }

    // -------------------------------------------------------------------------
    // Request DTOs
    // -------------------------------------------------------------------------

    public static class AccountRequest {
        private String     ownerName;
        private String     accountType;
        private BigDecimal initialBalance;

        public String getOwnerName()              { return ownerName; }
        public void setOwnerName(String v)        { this.ownerName = v; }

        public String getAccountType()            { return accountType; }
        public void setAccountType(String v)      { this.accountType = v; }

        public BigDecimal getInitialBalance()     { return initialBalance; }
        public void setInitialBalance(BigDecimal v) { this.initialBalance = v; }
    }

    public static class TransactionRequest {
        private String     type;
        private BigDecimal amount;
        private String     currency;
        private String     description;

        public String getType()            { return type; }
        public void setType(String v)      { this.type = v; }

        public BigDecimal getAmount()          { return amount; }
        public void setAmount(BigDecimal v)    { this.amount = v; }

        public String getCurrency()        { return currency; }
        public void setCurrency(String v)  { this.currency = v; }

        public String getDescription()     { return description; }
        public void setDescription(String v) { this.description = v; }
    }
}
