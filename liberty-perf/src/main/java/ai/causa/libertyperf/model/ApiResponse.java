package ai.causa.libertyperf.model;

/**
 * Generic API response wrapper with correlation tracing.
 */
public class ApiResponse<T> {

    private boolean  success;
    private String   message;
    private T        data;
    private String   correlationId;
    private long     processingTimeMs;
    private String   error;

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
}
