package ai.causa.libertyperf.health;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Readiness probe: verifies that the application can acquire a DB connection
 * and execute a trivial query.
 *
 * <p>When connection-pool exhaustion occurs (chaos mode) this probe fails
 * immediately, causing Kubernetes to stop routing traffic to the pod.
 */
@Readiness
@ApplicationScoped
public class ReadinessCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(ReadinessCheck.class.getName());

    @Resource(lookup = "jdbc/libertyPerfDS")
    DataSource dataSource;

    @Override
    public HealthCheckResponse call() {
        long start = System.currentTimeMillis();

        try (Connection conn = dataSource.getConnection();
             Statement stmt  = conn.createStatement();
             ResultSet rs    = stmt.executeQuery("SELECT 1")) {

            rs.next();
            long latencyMs = System.currentTimeMillis() - start;

            return HealthCheckResponse.named("liberty-perf/readiness")
                    .up()
                    .withData("db.ping.ms", latencyMs)
                    .withData("db.status", "reachable")
                    .build();

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            LOG.warning("[HEALTH] DB readiness check failed: " + e.getMessage());

            return HealthCheckResponse.named("liberty-perf/readiness")
                    .down()
                    .withData("db.ping.ms", latencyMs)
                    .withData("db.status", "unreachable")
                    .withData("error",     e.getMessage())
                    .build();
        }
    }
}
