package fu.se184491.loadmaster_be.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code optimization.engine.*} block from {@code application.yml}.
 *
 * <pre>
 * optimization:
 *   engine:
 *     url: http://localhost:8000
 *     timeout-sec: 120
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "optimization.engine")
public class OptimizationEngineProperties {

    /** Base URL of the FastAPI Optimization Engine, e.g. http://localhost:8000 */
    private String url = "http://localhost:8000";

    /** HTTP read/connect timeout in seconds for engine calls */
    private int timeoutSec = 120;
}
