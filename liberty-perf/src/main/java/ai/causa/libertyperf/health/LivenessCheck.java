package ai.causa.libertyperf.health;

import ai.causa.libertyperf.service.TransactionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness probe: reports DOWN if the application has leaked so much memory
 * that heap usage has exceeded a configurable threshold (default 90%).
 *
 * <p>Kubernetes will restart the pod when this probe fails, which is the
 * expected behaviour during a memory-pressure chaos scenario.
 */
@Liveness
@ApplicationScoped
public class LivenessCheck implements HealthCheck {

    private static final double HEAP_FAIL_THRESHOLD = 0.90; // 90%

    @Inject
    TransactionService transactionService;

    @Override
    public HealthCheckResponse call() {
        Runtime rt = Runtime.getRuntime();
        long maxHeap  = rt.maxMemory();
        long usedHeap = rt.totalMemory() - rt.freeMemory();
        double usedPct = (double) usedHeap / maxHeap;

        long leakCacheEntries = transactionService.getLeakCacheSize();
        long leakCacheKB      = transactionService.getLeakCacheBytes() / 1024;

        boolean alive = usedPct < HEAP_FAIL_THRESHOLD;

        return HealthCheckResponse.named("liberty-perf/liveness")
                .status(alive)
                .withData("heap.used.pct",    String.format("%.1f%%", usedPct * 100))
                .withData("heap.used.mb",      usedHeap / (1024 * 1024))
                .withData("heap.max.mb",       maxHeap  / (1024 * 1024))
                .withData("leak.cache.entries", leakCacheEntries)
                .withData("leak.cache.kb",      leakCacheKB)
                .withData("threshold.pct",     String.format("%.0f%%", HEAP_FAIL_THRESHOLD * 100))
                .build();
    }
}
