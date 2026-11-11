package fu.se184491.loadmaster_be.client;

import fu.se184491.loadmaster_be.dto.request.account.KeycloakRoleRepresentation;
import fu.se184491.loadmaster_be.dto.request.account.KeycloakUserRepresentation;
import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
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

        String firstName = "";
        String lastName = "";
        if (fullName != null && !fullName.trim().isEmpty()) {
            String trimmed = fullName.trim();
            int lastSpace = trimmed.lastIndexOf(' ');
            if (lastSpace > 0) {
                lastName = trimmed.substring(0, lastSpace);
                firstName = trimmed.substring(lastSpace + 1);
            } else {
                firstName = trimmed;
                lastName = trimmed;
            }
        }

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("username", email);
        body.put("email", email);
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("emailVerified", true);
        body.put("enabled", true);
        body.put("requiredActions", java.util.List.of());

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

    public void updateUserProfile(String keycloakUserId, String fullName) {
        String token = serviceTokenProvider.getKeycloakAdminToken();
        String firstName = "";
        String lastName = "";
        if (fullName != null && !fullName.trim().isEmpty()) {
            String trimmed = fullName.trim();
            int lastSpace = trimmed.lastIndexOf(' ');
            if (lastSpace > 0) {
                lastName = trimmed.substring(0, lastSpace);
                firstName = trimmed.substring(lastSpace + 1);
            } else {
                firstName = trimmed;
                lastName = trimmed;
            }
        }

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("requiredActions", java.util.List.of());

        keycloakAdminRestClient
                .put()
                .uri(
                        "/admin/realms/{realm}/users/{userId}",
                        REALM,
                        keycloakUserId
                )
                .headers(headers -> headers.setBearerAuth(token))
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Đặt mật khẩu cho user Keycloak.
     *
     * @param temporary {@code true}: mật khẩu tạm (tạo tài khoản, quản trị viên đặt lại) — Keycloak bắt đổi mật khẩu ở lần đăng nhập
     *                  sau, và password grant của app từ chối ("Account is not fully set up") tới khi đổi. {@code false}: mật khẩu chính
     *                  người dùng vừa đặt (đặt lại qua OTP), dùng đăng nhập được ngay.
     */
    public void setPassword(
            String keycloakUserId,
            String password,
            boolean temporary
    ) {
        String token = serviceTokenProvider.getKeycloakAdminToken();

        Map<String, Object> body = Map.of(
                "type", "password",
                "value", password,
                "temporary", temporary
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

        if (!temporary) {
            clearRequiredActions(keycloakUserId);
        }
    }

    /**
     * Xóa sạch các requiredActions (như UPDATE_PASSWORD) trên user Keycloak để tránh bị bắt đổi mật khẩu lặp lại
     * khi đăng nhập qua Direct Access Grants.
     */
    public void clearRequiredActions(String keycloakUserId) {
        String token = serviceTokenProvider.getKeycloakAdminToken();
        Map<String, Object> body = Map.of("requiredActions", List.of());

        keycloakAdminRestClient
                .put()
                .uri(
                        "/admin/realms/{realm}/users/{userId}",
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

    public void setUserEnabled(String keycloakUserId, boolean enabled) {
        String token = serviceTokenProvider.getKeycloakAdminToken();
        Map<String, Object> body = Map.of("enabled", enabled);

        keycloakAdminRestClient
                .put()
                .uri(
                        "/admin/realms/{realm}/users/{userId}",
                        REALM,
                        keycloakUserId
                )
                .headers(headers -> headers.setBearerAuth(token))
                .body(body)
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