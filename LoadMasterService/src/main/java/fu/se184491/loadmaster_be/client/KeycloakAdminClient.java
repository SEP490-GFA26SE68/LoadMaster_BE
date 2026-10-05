package fu.se184491.loadmaster_be.client;

import fu.se184491.loadmaster_be.dto.request.account.KeycloakRoleRepresentation;
import fu.se184491.loadmaster_be.dto.request.account.KeycloakUserRepresentation;
import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
public class KeycloakAdminClient {

    private static final String REALM = "loadmaster";

    private final RestClient keycloakAdminRestClient;
    private final ServiceTokenProvider serviceTokenProvider;

    public KeycloakAdminClient(
            @Qualifier("keycloakAdminRestClient")
            RestClient keycloakAdminRestClient,
            ServiceTokenProvider serviceTokenProvider
    ) {
        this.keycloakAdminRestClient = keycloakAdminRestClient;
        this.serviceTokenProvider = serviceTokenProvider;
    }

    public String createUser(
            String email,
            String fullName
    ) {
        String token = serviceTokenProvider.getKeycloakAdminToken();

        Map<String, Object> body = Map.of(
                "username", email,
                "email", email,
                "emailVerified", true,
                "enabled", true,
                "firstName", fullName
        );

        ResponseEntity<Void> response =
                keycloakAdminRestClient
                        .post()
                        .uri("/admin/realms/{realm}/users", REALM)
                        .headers(headers -> headers.setBearerAuth(token))
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();

        URI location = response.getHeaders().getLocation();

        if (location == null) {
            throw new IllegalStateException(
                    "Keycloak did not return created user location"
            );
        }

        String path = location.getPath();

        return path.substring(path.lastIndexOf("/") + 1);
    }

    public void setPassword(
            String keycloakUserId,
            String password
    ) {
        String token = serviceTokenProvider.getKeycloakAdminToken();

        Map<String, Object> body = Map.of(
                "type", "password",
                "value", password,
                "temporary", true
        );

        keycloakAdminRestClient
                .put()
                .uri(
                        "/admin/realms/{realm}/users/{userId}/reset-password",
                        REALM,
                        keycloakUserId
                )
                .headers(headers -> headers.setBearerAuth(token))
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    public void deleteUser(String keycloakUserId) {
        String token = serviceTokenProvider.getKeycloakAdminToken();

        keycloakAdminRestClient
                .delete()
                .uri(
                        "/admin/realms/{realm}/users/{userId}",
                        REALM,
                        keycloakUserId
                )
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .toBodilessEntity();
    }

    public void assignRealmRole(
            String keycloakUserId,
            String roleName
    ) {
        String token = serviceTokenProvider.getKeycloakAdminToken();

        KeycloakRoleRepresentation role =
                keycloakAdminRestClient
                        .get()
                        .uri(
                                "/admin/realms/{realm}/roles/{roleName}",
                                REALM,
                                roleName
                        )
                        .headers(headers -> headers.setBearerAuth(token))
                        .retrieve()
                        .body(KeycloakRoleRepresentation.class);

        if (role == null) {
            throw new IllegalStateException(
                    "Keycloak role not found: " + roleName
            );
        }

        keycloakAdminRestClient
                .post()
                .uri(
                        "/admin/realms/{realm}/users/{userId}/role-mappings/realm",
                        REALM,
                        keycloakUserId
                )
                .headers(headers -> headers.setBearerAuth(token))
                .body(List.of(role))
                .retrieve()
                .toBodilessEntity();
    }

    public String findUserIdByEmail(String email) {

        String token = serviceTokenProvider.getKeycloakAdminToken();

        KeycloakUserRepresentation[] users =
                keycloakAdminRestClient
                        .get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/admin/realms/{realm}/users")
                                .queryParam("email", email)
                                .queryParam("exact", true)
                                .build(REALM)
                        )
                        .headers(headers ->
                                headers.setBearerAuth(token)
                        )
                        .retrieve()
                        .body(KeycloakUserRepresentation[].class);

        if (users == null || users.length == 0) {
            return null;
        }

        return users[0].id();
    }
}