package ai.causa.libertyperf.health;

import ai.causa.libertyperf.service.ResponsePaddingService;
import ai.causa.libertyperf.service.TransactionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness probe: reports DOWN if heap usage has exceeded 90%.
 *
 * <p>Exposes both chaos knob states so the signal is clearly attributed
 * in Kubernetes events and Prometheus:
 * <ul>
 *   <li>{@code chaos.http.padding.enabled} — HTTP large-response knob active</li>
 *   <li>{@code chaos.http.padding.kb} — configured padding size</li>
 *   <li>{@code chaos.http.total.padding.mb} — cumulative MB padded since startup</li>
 *   <li>{@code chaos.leak.cache.entries} — background heap-leak cache size</li>
 * </ul>
 */
@Liveness
@ApplicationScoped
public class LivenessCheck implements HealthCheck {

    private static final double HEAP_FAIL_THRESHOLD = 0.90; // 90%

    @Inject
    TransactionService transactionService;

    @Inject
    ResponsePaddingService paddingService;

    @Override
    public HealthCheckResponse call() {
        Runtime rt = Runtime.getRuntime();
        long maxHeap  = rt.maxMemory();
        long usedHeap = rt.totalMemory() - rt.freeMemory();
        double usedPct = (double) usedHeap / maxHeap;

        long leakCacheEntries = transactionService.getLeakCacheSize();
        long leakCacheKB      = transactionService.getLeakCacheBytes() / 1024;
        long totalPaddingMb   = paddingService.getTotalPaddingBytes() / (1024 * 1024);

        boolean alive = usedPct < HEAP_FAIL_THRESHOLD;

        return HealthCheckResponse.named("liberty-perf/liveness")
                .status(alive)
                .withData("heap.used.pct",              String.format("%.1f%%", usedPct * 100))
                .withData("heap.used.mb",               usedHeap / (1024 * 1024))
                .withData("heap.max.mb",                maxHeap  / (1024 * 1024))
                .withData("threshold.pct",              String.format("%.0f%%", HEAP_FAIL_THRESHOLD * 100))
                .withData("chaos.http.padding.enabled", paddingService.isEnabled())
                .withData("chaos.http.padding.kb",      paddingService.getPaddingKb())
                .withData("chaos.http.total.padding.mb", totalPaddingMb)
                .withData("chaos.leak.cache.entries",   leakCacheEntries)
                .withData("chaos.leak.cache.kb",        leakCacheKB)
                .build();
    }
}
