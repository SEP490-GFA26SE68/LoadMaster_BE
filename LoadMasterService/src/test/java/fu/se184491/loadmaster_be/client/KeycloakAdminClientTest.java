package fu.se184491.loadmaster_be.client;

import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

class KeycloakAdminClientTest {

    private MockRestServiceServer server;
    private KeycloakAdminClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://keycloak.test");
        server = MockRestServiceServer.bindTo(builder).build();
        ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
        when(tokens.getKeycloakAdminToken()).thenReturn("admin-token");
        client = new KeycloakAdminClient(builder.build(), tokens);
    }

    @ParameterizedTest(name = "temporary = {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("setPassword gửi đúng cờ temporary và xóa requiredActions khi đặt mật khẩu chính thức")
    void setPasswordSendsTemporaryFlag(boolean temporary) {
        server.expect(requestTo("http://keycloak.test/admin/realms/loadmaster/users/kc-1/reset-password"))
                .andExpect(method(PUT))
                .andExpect(header("Authorization", "Bearer admin-token"))
                .andExpect(jsonPath("$.type").value("password"))
                .andExpect(jsonPath("$.value").value("MatKhau#1"))
                .andExpect(jsonPath("$.temporary").value(temporary))
                .andRespond(withNoContent());

        if (!temporary) {
            server.expect(requestTo("http://keycloak.test/admin/realms/loadmaster/users/kc-1"))
                    .andExpect(method(PUT))
                    .andExpect(header("Authorization", "Bearer admin-token"))
                    .andExpect(jsonPath("$.requiredActions").isArray())
                    .andExpect(jsonPath("$.requiredActions.length()").value(0))
                    .andRespond(withNoContent());
        }

        client.setPassword("kc-1", "MatKhau#1", temporary);

        server.verify();
    }

    @Test
    @DisplayName("clearRequiredActions gửi danh sách requiredActions rỗng lên Keycloak")
    void clearRequiredActionsSendsEmptyList() {
        server.expect(requestTo("http://keycloak.test/admin/realms/loadmaster/users/kc-99"))
                .andExpect(method(PUT))
                .andExpect(header("Authorization", "Bearer admin-token"))
                .andExpect(jsonPath("$.requiredActions").isArray())
                .andExpect(jsonPath("$.requiredActions.length()").value(0))
                .andRespond(withNoContent());

        client.clearRequiredActions("kc-99");

        server.verify();
    }
}
