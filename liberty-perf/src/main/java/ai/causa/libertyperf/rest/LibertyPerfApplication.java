package ai.causa.libertyperf.rest;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;

/**
 * JAX-RS application entry point.
 */
@ApplicationPath("/")
@OpenAPIDefinition(
    info = @Info(
        title = "Liberty Performance Benchmark API",
        version = "1.0.0",
        description = "Transactional benchmark application simulating banking and airline booking workloads. " +
                      "Includes configurable chaos knobs for connection leaks and memory pressure.",
        contact = @Contact(name = "Causa AI", url = "https://github.com/causaai"),
        license = @License(name = "Apache 2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")
    )
)
public class LibertyPerfApplication extends Application {
}
