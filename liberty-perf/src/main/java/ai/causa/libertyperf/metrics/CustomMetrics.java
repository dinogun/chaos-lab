package ai.causa.libertyperf.metrics;

import ai.causa.libertyperf.service.ResponsePaddingService;
import ai.causa.libertyperf.service.TransactionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.RegistryScope;

import jakarta.annotation.PostConstruct;

/**
 * Registers custom application-level Prometheus gauges.
 *
 * <p>Metrics exposed:
 * <ul>
 *   <li>{@code liberty_perf_heap_used_bytes}         — current JVM heap usage</li>
 *   <li>{@code liberty_perf_heap_max_bytes}          — configured JVM max heap</li>
 *   <li>{@code liberty_perf_heap_used_pct}           — heap utilisation (0–1)</li>
 *   <li>{@code liberty_perf_leak_cache_entries}      — background heap-leak cache entries</li>
 *   <li>{@code liberty_perf_leak_cache_bytes}        — bytes held by background leak cache</li>
 *   <li>{@code liberty_perf_http_padding_total_bytes} — cumulative bytes padded into HTTP responses</li>
 *   <li>{@code liberty_perf_http_padding_enabled}    — 1.0 if HTTP large-response chaos is active</li>
 * </ul>
 */
@ApplicationScoped
public class CustomMetrics {

    @Inject
    @RegistryScope(scope = MetricRegistry.APPLICATION_SCOPE)
    MetricRegistry registry;

    @Inject
    TransactionService transactionService;

    @Inject
    ResponsePaddingService paddingService;

    @PostConstruct
    void register() {
        registry.gauge("liberty_perf_heap_used_bytes", () -> {
            Runtime rt = Runtime.getRuntime();
            return (double) (rt.totalMemory() - rt.freeMemory());
        });

        registry.gauge("liberty_perf_heap_max_bytes", () -> (double) Runtime.getRuntime().maxMemory());

        registry.gauge("liberty_perf_heap_used_pct", () -> {
            Runtime rt = Runtime.getRuntime();
            long max  = rt.maxMemory();
            long used = rt.totalMemory() - rt.freeMemory();
            return max > 0 ? (double) used / max : 0.0;
        });

        registry.gauge("liberty_perf_leak_cache_entries",
                () -> (double) transactionService.getLeakCacheSize());

        registry.gauge("liberty_perf_leak_cache_bytes",
                () -> (double) transactionService.getLeakCacheBytes());

        // HTTP large-response chaos gauges
        registry.gauge("liberty_perf_http_padding_total_bytes",
                () -> (double) paddingService.getTotalPaddingBytes());

        registry.gauge("liberty_perf_http_padding_enabled",
                () -> paddingService.isEnabled() ? 1.0 : 0.0);
    }
}
