package fu.se184491.loadmaster_be.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Provides shared infrastructure beans used by multiple services.
 */
@Configuration
public class InfrastructureConfig {

    /**
     * General-purpose {@link RestTemplate} used by:
     * <ul>
     *   <li>{@link fu.se184491.loadmaster_be.service.auth.KeycloakServiceTokenProvider}
     *       — fetches service tokens from Keycloak</li>
     *   <li>{@link fu.se184491.loadmaster_be.service.optimize.Impl.HttpOptimizationClient}
     *       — calls FastAPI engine</li>
     * </ul>
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
