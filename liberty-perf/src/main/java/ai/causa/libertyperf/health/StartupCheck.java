package ai.causa.libertyperf.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Startup;

/**
 * Startup probe: reports UP once the JVM is fully initialised.
 * Liberty calls @PostConstruct before accepting traffic, so by the time
 * this probe is ever queried the application is always ready.
 */
@Startup
@ApplicationScoped
public class StartupCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("liberty-perf/startup")
                .up()
                .withData("status", "Application started successfully")
                .build();
    }
}
