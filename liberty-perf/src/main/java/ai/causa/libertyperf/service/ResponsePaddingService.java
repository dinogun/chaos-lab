package ai.causa.libertyperf.service;

import ai.causa.libertyperf.model.ApiResponse;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.metrics.annotation.Counted;

import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Chaos knob: HTTP large-response padding.
 *
 * <h3>What it models</h3>
 * In production Liberty deployments a common misconfiguration is pairing a high
 * {@code persistTimeout} (keep-alive window) with large response bodies. Each
 * Liberty I/O thread holds the full serialised response in heap from the moment
 * JAX-RS begins writing the body until the socket flushes and the connection is
 * either returned to the pool or timed out.  Under concurrent load this creates
 * sustained heap pressure proportional to:
 *
 * <pre>
 *   live_heap ≈ concurrent_connections × response_size_bytes
 * </pre>
 *
 * At 50 concurrent workers, 256 KB per response and a 60-second keep-alive
 * window, the JVM must hold roughly 12–50 MB of live response data at any given
 * moment — enough to tip a 384 MB heap (512 Mi limit, 75% MaxRAMPercentage)
 * into GC pressure and eventual OOM within minutes.
 *
 * <h3>How to activate</h3>
 * Set {@code CHAOS_HTTP_LARGE_RESPONSE_ENABLED=true} in the pod's environment
 * (or in the ConfigMap).  Adjust {@code CHAOS_HTTP_LARGE_RESPONSE_KB} to tune
 * the pressure level.  The {@code HTTP_PERSIST_TIMEOUT_S} Liberty variable
 * controls how long connections linger; raise it to 60 s to amplify the effect.
 *
 * <h3>Why this is different from the heap-leak cache</h3>
 * The heap-leak cache ({@code CHAOS_MEMORY_CACHE_ENABLED}) grows unboundedly in
 * the background regardless of HTTP activity.  This knob is directly tied to the
 * HTTP keep-alive window: memory pressure tracks request rate and connection
 * lifetime rather than total historical call count.  That makes it a faithful
 * reproduction of the RCA scenario described in the issue.
 */
@ApplicationScoped
public class ResponsePaddingService {

    private static final Logger LOG = Logger.getLogger(ResponsePaddingService.class.getName());

    /** Total bytes padded across all responses since startup. */
    private static final AtomicLong TOTAL_PADDING_BYTES = new AtomicLong();

    @ConfigProperty(name = "chaos.http.large.response.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "chaos.http.large.response.kb", defaultValue = "256")
    int paddingKb;

    /**
     * If the knob is active, allocates a byte array of the configured size,
     * Base64-encodes it, and sets it on the response's {@code debugPadding}
     * field.  The Base64 string is roughly 1.33× the raw byte size, so the
     * effective per-response heap cost while JSON-B serialises it is:
     *
     * <pre>
     *   raw_bytes  (byte[])    +  base64_string  (char[] inside String)
     *   ≈ paddingKb * 1024      +  paddingKb * 1024 * 4/3
     *   ≈ 2.33 × paddingKb KB
     * </pre>
     *
     * @param response the {@link ApiResponse} to pad; mutated in-place
     */
    @Counted(name = "chaos.http.padded.responses",
             description = "Number of HTTP responses padded by the large-response chaos knob")
    public <T> void pad(ApiResponse<T> response) {
        if (!enabled) {
            return;
        }

        int size = Math.max(1, paddingKb) * 1024;
        byte[] raw = new byte[size];
        Arrays.fill(raw, (byte) 'P');                     // deterministic, GC-unfriendly fill
        String encoded = Base64.getEncoder().encodeToString(raw);
        response.setDebugPadding(encoded);

        long total = TOTAL_PADDING_BYTES.addAndGet(size);
        LOG.warning(String.format(
                "[CHAOS-HTTP] Response padded with %d KB. Total padding issued: %d MB. " +
                "persistTimeout is controlled by HTTP_PERSIST_TIMEOUT_S. " +
                "Set CHAOS_HTTP_LARGE_RESPONSE_ENABLED=false to stop.",
                paddingKb, total / (1024 * 1024)));
    }

    /** Returns cumulative bytes padded since startup (for metrics). */
    public long getTotalPaddingBytes() {
        return TOTAL_PADDING_BYTES.get();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPaddingKb() {
        return paddingKb;
    }
}
