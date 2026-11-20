package fu.se184491.loadmaster_be.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component("creditServiceAuthorization")
public class CreditServiceAuthorization {
    private final String clientId;

    public CreditServiceAuthorization(@Value("${loadmaster.security.credit-service-client-id:optimization-service}") String clientId) {
        this.clientId = clientId;
    }

    public boolean isAllowed(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
            return false;
        }
        return jwt.getToken().getAudience().contains("loadmaster-api")
                && clientId.equals(jwt.getToken().getClaimAsString("azp"))
                && ("service-account-" + clientId).equals(jwt.getToken().getClaimAsString("preferred_username"))
                && authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_OPTIMIZATION_CREDIT"));
    }
}
