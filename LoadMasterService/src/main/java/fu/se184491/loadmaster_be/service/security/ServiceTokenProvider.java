package fu.se184491.loadmaster_be.service.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ServiceTokenProvider {

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public String getOptimizationServiceToken() {

        Authentication principal =
                new UsernamePasswordAuthenticationToken(
                        "loadmaster-backend-service",
                        null,
                        List.of()
                );

        OAuth2AuthorizeRequest request =
                OAuth2AuthorizeRequest
                        .withClientRegistrationId("optimization-client")
                        .principal(principal)
                        .build();

        OAuth2AuthorizedClient client =
                authorizedClientManager.authorize(request);

        if (client == null) {
            throw new IllegalStateException(
                    "Cannot obtain optimization service access token"
            );
        }

        return client.getAccessToken().getTokenValue();
    }
}