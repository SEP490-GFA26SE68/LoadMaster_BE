package fu.se184491.loadmaster_be.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code keycloak.service-client.*} block from {@code application.yml}.
 *
 * <pre>
 * keycloak:
 *   auth-server-url: http://localhost:8180
 *   realm: loadmaster
 *   service-client:
 *     client-id: loadmaster-backend-service
 *     client-secret: ${KEYCLOAK_SERVICE_CLIENT_SECRET}
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "keycloak")
public class KeycloakServiceProperties {

    /** Base URL of the Keycloak server, e.g. http://localhost:8180 */
    private String authServerUrl;

    /** Realm name, e.g. loadmaster */
    private String realm;

    /** Nested block keycloak.service-client.* */
    private ServiceClient serviceClient = new ServiceClient();

    @Getter
    @Setter
    public static class ServiceClient {
        private String clientId;
        private String clientSecret;
    }

    /**
     * Convenience: full Keycloak token endpoint URL.
     */
    public String getTokenEndpoint() {
        return authServerUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }
}
