package ai.causa.libertyperf.model;

/**
 * Generic API response wrapper with correlation tracing.
 *
 * <p>When the HTTP large-response chaos knob is active, the {@code debugPadding}
 * field carries a Base64-encoded byte array whose size is configured via
 * {@code chaos.http.large.response.kb}. This forces Liberty's JAX-RS response
 * serialiser to materialise the full payload in heap memory on the I/O thread
 * before writing it to the socket — reproducing the memory pressure that arises
 * from HTTP keep-alive misconfiguration (large responses held alive on many
 * concurrent connections simultaneously).
 */
public class ApiResponse<T> {

    private boolean  success;
    private String   message;
    private T        data;
    private String   correlationId;
    private long     processingTimeMs;
    private String   error;
    /**
     * Chaos padding: non-null only when {@code chaos.http.large.response.enabled=true}.
     * Intentionally a plain String so JSON-B writes it inline in the response body,
     * keeping the full allocation live on the Liberty I/O thread during serialisation.
     */
    private String   debugPadding;

    public ApiResponse() {}

    public static <T> ApiResponse<T> ok(T data, String correlationId, long processingTimeMs) {
        ApiResponse<T> r = new ApiResponse<>();
        r.success          = true;
        r.data             = data;
        r.correlationId    = correlationId;
        r.processingTimeMs = processingTimeMs;
        return r;
    }

    public static <T> ApiResponse<T> error(String message, String error, String correlationId) {
        ApiResponse<T> r = new ApiResponse<>();
        r.success       = false;
        r.message       = message;
        r.error         = error;
        r.correlationId = correlationId;
        return r;
    }

    // ---- getters / setters ----

    public boolean isSuccess()                     { return success; }
    public void setSuccess(boolean v)              { this.success = v; }

    public String getMessage()                     { return message; }
    public void setMessage(String v)               { this.message = v; }

    public T getData()                             { return data; }
    public void setData(T v)                       { this.data = v; }

    public String getCorrelationId()               { return correlationId; }
    public void setCorrelationId(String v)         { this.correlationId = v; }

    public long getProcessingTimeMs()              { return processingTimeMs; }
    public void setProcessingTimeMs(long v)        { this.processingTimeMs = v; }

    public String getError()                       { return error; }
    public void setError(String v)                 { this.error = v; }

    public String getDebugPadding()                { return debugPadding; }
    public void setDebugPadding(String v)          { this.debugPadding = v; }
}
