package ai.causa.libertyperf.load;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.*;

/**
 * Standalone load-generator executable.
 *
 * <p>Run as a Kubernetes Job alongside the main application.
 * Configuration is entirely via environment variables so the same container image
 * can be used for different load profiles.
 *
 * <h3>Environment variables</h3>
 * <table border="1">
 * <tr><th>Variable</th><th>Default</th><th>Description</th></tr>
 * <tr><td>TARGET_HOST</td><td>liberty-perf-svc</td><td>Service hostname</td></tr>
 * <tr><td>TARGET_PORT</td><td>9080</td><td>Service port</td></tr>
 * <tr><td>CONCURRENT_USERS</td><td>20</td><td>Number of parallel threads</td></tr>
 * <tr><td>DURATION_SECONDS</td><td>300</td><td>Total run duration</td></tr>
 * <tr><td>REQUEST_DELAY_MS</td><td>100</td><td>Delay between requests per thread</td></tr>
 * <tr><td>BOOKING_RATIO</td><td>50</td><td>% of requests that are bookings (rest are transactions)</td></tr>
 * </table>
 */
public class LoadGenerator {

    private static final Logger LOG = Logger.getLogger(LoadGenerator.class.getName());

    // ── Config from env ──────────────────────────────────────────────────────
    private static final String TARGET_HOST      = env("TARGET_HOST",      "liberty-perf-svc");
    private static final int    TARGET_PORT      = intEnv("TARGET_PORT",   9080);
    private static final int    CONCURRENT_USERS = intEnv("CONCURRENT_USERS", 20);
    private static final int    DURATION_SECONDS = intEnv("DURATION_SECONDS", 300);
    private static final long   REQUEST_DELAY_MS = longEnv("REQUEST_DELAY_MS", 100);
    private static final int    BOOKING_RATIO    = intEnv("BOOKING_RATIO",  50);   // percent

    private static final String BASE_URL = "http://" + TARGET_HOST + ":" + TARGET_PORT;

    // ── Metrics ──────────────────────────────────────────────────────────────
    private static final AtomicLong totalRequests  = new AtomicLong();
    private static final AtomicLong successCount   = new AtomicLong();
    private static final AtomicLong errorCount     = new AtomicLong();
    private static final AtomicLong totalLatencyMs = new AtomicLong();

    // ── Account IDs available in seed data ──────────────────────────────────
    private static final String[] ACCOUNT_IDS = {
        "ACC-001","ACC-002","ACC-003","ACC-004","ACC-005",
        "ACC-006","ACC-007","ACC-008","ACC-009","ACC-010"
    };

    private static final String[][] ROUTES = {
        {"JFK","LAX"},{"LAX","ORD"},{"ORD","ATL"},{"ATL","DFW"},{"DFW","DEN"},
        {"DEN","SFO"},{"SFO","SEA"},{"SEA","LAS"},{"LAS","MCO"},{"MCO","JFK"}
    };

    // ── HTTP client (shared across all threads) ──────────────────────────────
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    public static void main(String[] args) throws Exception {
        configureLogging();
        LOG.info("=== Liberty Perf Load Generator starting ===");
        LOG.info("Target:           " + BASE_URL);
        LOG.info("Concurrent users: " + CONCURRENT_USERS);
        LOG.info("Duration:         " + DURATION_SECONDS + "s");
        LOG.info("Request delay:    " + REQUEST_DELAY_MS + "ms/thread");
        LOG.info("Booking ratio:    " + BOOKING_RATIO + "%");

        // Wait for application to be ready
        waitForReady();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor();

        long endTime = System.currentTimeMillis() + (DURATION_SECONDS * 1000L);

        // Progress reporter every 30 seconds
        reporter.scheduleAtFixedRate(() -> printStats(), 30, 30, TimeUnit.SECONDS);

        // Submit worker tasks
        for (int i = 0; i < CONCURRENT_USERS; i++) {
            final int workerId = i;
            executor.submit(() -> runWorker(workerId, endTime));
        }

        executor.shutdown();
        executor.awaitTermination(DURATION_SECONDS + 60L, TimeUnit.SECONDS);
        reporter.shutdownNow();

        printStats();
        LOG.info("=== Load Generator completed ===");
        System.exit(errorCount.get() > (totalRequests.get() * 0.5) ? 1 : 0);
    }

    // ── Worker ───────────────────────────────────────────────────────────────

    private static void runWorker(int workerId, long endTime) {
        Random rng = new Random(workerId);
        LOG.info("[Worker-" + workerId + "] Starting");

        while (System.currentTimeMillis() < endTime) {
            try {
                if (rng.nextInt(100) < BOOKING_RATIO) {
                    sendBookingRequest(workerId, rng);
                } else {
                    sendTransactionRequest(workerId, rng);
                }

                if (REQUEST_DELAY_MS > 0) {
                    Thread.sleep(REQUEST_DELAY_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.warning("[Worker-" + workerId + "] Unexpected error: " + e.getMessage());
                errorCount.incrementAndGet();
            }
        }

        LOG.info("[Worker-" + workerId + "] Finished");
    }

    // ── Request builders ─────────────────────────────────────────────────────

    private static void sendTransactionRequest(int workerId, Random rng) {
        String accountId = ACCOUNT_IDS[rng.nextInt(ACCOUNT_IDS.length)];
        String[] types   = {"CREDIT", "DEBIT"};
        String   type    = types[rng.nextInt(types.length)];
        double   amount  = 10 + rng.nextInt(990);

        String body = String.format(
                "{\"type\":\"%s\",\"amount\":%s,\"currency\":\"USD\",\"description\":\"Load test tx from worker %d\"}",
                type, amount, workerId);

        String url = BASE_URL + "/api/accounts/" + accountId + "/transactions";
        sendPost(url, body, workerId);
    }

    private static void sendBookingRequest(int workerId, Random rng) {
        String[] route       = ROUTES[rng.nextInt(ROUTES.length)];
        String   passengerId = "PAX-" + String.format("%04d", rng.nextInt(1000));

        String body = String.format(
                "{\"passengerId\":\"%s\",\"passengerName\":\"LoadTestPassenger-%s\",\"origin\":\"%s\",\"destination\":\"%s\"}",
                passengerId, passengerId, route[0], route[1]);

        String url = BASE_URL + "/api/bookings";
        sendPost(url, body, workerId);
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private static void sendPost(String url, String body, int workerId) {
        long start = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        String correlationId = UUID.randomUUID().toString().substring(0, 8);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-Correlation-ID", correlationId)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            totalLatencyMs.addAndGet(latency);

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                successCount.incrementAndGet();
                LOG.fine(String.format("[Worker-%d][%s] %d %s (%dms)",
                        workerId, correlationId, response.statusCode(), url, latency));
            } else {
                errorCount.incrementAndGet();
                LOG.warning(String.format("[Worker-%d][%s] HTTP %d %s (%dms) body=%s",
                        workerId, correlationId, response.statusCode(), url, latency,
                        truncate(response.body(), 200)));
            }
        } catch (Exception e) {
            errorCount.incrementAndGet();
            totalLatencyMs.addAndGet(System.currentTimeMillis() - start);
            LOG.warning(String.format("[Worker-%d][%s] Request failed %s: %s",
                    workerId, correlationId, url, e.getMessage()));
        }
    }

    // ── Readiness wait ───────────────────────────────────────────────────────

    private static void waitForReady() throws InterruptedException {
        String healthUrl = BASE_URL + "/health/ready";
        LOG.info("Waiting for application to be ready at " + healthUrl);

        for (int attempt = 1; attempt <= 30; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(healthUrl))
                        .GET()
                        .timeout(Duration.ofSeconds(3))
                        .build();
                HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    LOG.info("Application is ready (attempt " + attempt + ")");
                    return;
                }
                LOG.info("Not ready yet (HTTP " + r.statusCode() + "), attempt " + attempt + "/30");
            } catch (Exception e) {
                LOG.info("Not ready yet (" + e.getMessage() + "), attempt " + attempt + "/30");
            }
            Thread.sleep(5000);
        }
        LOG.warning("Application did not become ready after 30 attempts — proceeding anyway");
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    private static void printStats() {
        long total   = totalRequests.get();
        long success = successCount.get();
        long errors  = errorCount.get();
        long avgMs   = total > 0 ? totalLatencyMs.get() / total : 0;

        LOG.info(String.format(
                "=== STATS | total=%d success=%d errors=%d errorRate=%.1f%% avgLatency=%dms ===",
                total, success, errors,
                total > 0 ? (errors * 100.0 / total) : 0.0,
                avgMs));
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() ? v : def;
    }

    private static int intEnv(String name, int def) {
        try { return Integer.parseInt(env(name, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    private static long longEnv(String name, long def) {
        try { return Long.parseLong(env(name, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static void configureLogging() {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tFT%1$tT.%1$tLZ %4$-7s [%2$s] %5$s%6$s%n");
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        for (Handler h : root.getHandlers()) {
            h.setLevel(Level.INFO);
            h.setFormatter(new SimpleFormatter());
        }
    }
}
