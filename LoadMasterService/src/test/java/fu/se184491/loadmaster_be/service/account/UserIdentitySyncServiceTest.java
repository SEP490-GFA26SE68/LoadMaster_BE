package fu.se184491.loadmaster_be.service.account;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.dto.request.account.KeycloakRoleRepresentation;
import fu.se184491.loadmaster_be.dto.request.account.KeycloakUserRepresentation;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserIdentitySyncServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private KeycloakAdminClient keycloakAdminClient;

    @InjectMocks private UserIdentitySyncService syncService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(syncService, "syncPassword", "Admin@12345");
    }

    @Test
    @DisplayName("Keycloak có, DB không: tạo hàng users, không đặt lại mật khẩu Keycloak")
    void keycloakOnlyCreatesDbUser() {
        when(keycloakAdminClient.listUsers()).thenReturn(List.of(kcUser(
                "kc-1", "driver@loadmaster.local", "driver@loadmaster.local", null)));
        when(userRepository.findAll()).thenReturn(List.of());
        when(keycloakAdminClient.listRealmRoles("kc-1"))
                .thenReturn(List.of(new KeycloakRoleRepresentation("r1", "DRIVER")));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserIdentitySyncService.Result result = syncService.syncMissingUsers();

        assertEquals(1, result.createdInDb());
        assertEquals(0, result.createdInKeycloak());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("kc-1", captor.getValue().getKeycloakId());
        assertEquals("driver@loadmaster.local", captor.getValue().getEmail());
        assertEquals(UserRole.DRIVER, captor.getValue().getUserRoleType());
        verify(keycloakAdminClient, never()).createUser(anyString(), anyString());
        verify(keycloakAdminClient, never()).setPassword(anyString(), anyString(), any(Boolean.class));
    }

    @Test
    @DisplayName("DB có, Keycloak không: tạo user Keycloak và gắn keycloak_id")
    void dbOnlyCreatesKeycloakUser() {
        User dbUser = User.builder()
                .id(24L)
                .email("tndriver01@loadmaster.local")
                .fullName("TN Driver")
                .keycloakId("old-kc")
                .userRoleType(UserRole.DRIVER)
                .build();
        when(keycloakAdminClient.listUsers()).thenReturn(List.of());
        when(userRepository.findAll()).thenReturn(List.of(dbUser));
        when(keycloakAdminClient.createUser("tndriver01@loadmaster.local", "TN Driver"))
                .thenReturn("kc-new");

        UserIdentitySyncService.Result result = syncService.syncMissingUsers();

        assertEquals(0, result.createdInDb());
        assertEquals(1, result.createdInKeycloak());
        verify(keycloakAdminClient).setPassword("kc-new", "Admin@12345", false);
        verify(keycloakAdminClient).assignRealmRole("kc-new", "DRIVER");
        verify(userRepository).updateKeycloakId(24L, "kc-new");
    }

    @Test
    @DisplayName("Cả hai đã có cùng email và keycloak_id: không tạo thêm")
    void matchingAccountsStayUnchanged() {
        User dbUser = User.builder()
                .id(21L)
                .email("admin@loadmaster.com")
                .fullName("Admin")
                .keycloakId("kc-admin")
                .userRoleType(UserRole.ADMIN)
                .build();
        when(keycloakAdminClient.listUsers()).thenReturn(List.of(kcUser(
                "kc-admin", "admin@loadmaster.com", "admin@loadmaster.com", null)));
        when(userRepository.findAll()).thenReturn(List.of(dbUser));

        UserIdentitySyncService.Result result = syncService.syncMissingUsers();

        assertEquals(0, result.createdInDb());
        assertEquals(0, result.createdInKeycloak());
        assertEquals(0, result.relinked());
        verify(userRepository, never()).save(any(User.class));
        verify(keycloakAdminClient, never()).createUser(anyString(), anyString());
    }

    @Test
    @DisplayName("Bỏ qua service account Keycloak")
    void skipsServiceAccounts() {
        when(keycloakAdminClient.listUsers()).thenReturn(List.of(kcUser(
                "kc-sa",
                "service-account-loadmaster-backend-service",
                null,
                "loadmaster-backend-service")));
        when(userRepository.findAll()).thenReturn(List.of());

        UserIdentitySyncService.Result result = syncService.syncMissingUsers();

        assertEquals(0, result.createdInDb());
        verify(userRepository, never()).save(any(User.class));
        verify(keycloakAdminClient, never()).listRealmRoles(anyString());
    }

    private static KeycloakUserRepresentation kcUser(
            String id,
            String username,
            String email,
            String serviceAccountClientId
    ) {
        return new KeycloakUserRepresentation(
                id, username, email, null, null, true, serviceAccountClientId);
    }
}
