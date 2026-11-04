package fu.se184491.loadmaster_be.bootstrap;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevAdminInitializerTest {

    private static final String EMAIL = "admin@loadmaster.local";

    @Mock private UserRepository userRepository;
    @Mock private KeycloakAdminClient keycloakAdminClient;

    @InjectMocks private DevAdminInitializer initializer;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(initializer, "adminEmail", EMAIL);
        ReflectionTestUtils.setField(initializer, "adminPassword", "MatKhauThu#1");
        ReflectionTestUtils.setField(initializer, "adminFullName", "LoadMaster Admin");
    }

    private void relink(boolean value) {
        ReflectionTestUtils.setField(initializer, "relink", value);
    }

    private User adminInDb(String keycloakId) {
        User user = User.builder().email(EMAIL).keycloakId(keycloakId).userRoleType(UserRole.SYSTEM_ADMIN).build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    @DisplayName("Admin trong DB gắn Keycloak khác: mặc định không xoá, không sửa gì")
    void foreignKeycloakIdLeavesDbAlone() {
        relink(false);
        adminInDb("kc-cua-nhom");
        when(keycloakAdminClient.findUserIdByEmail(EMAIL)).thenReturn("kc-may-nay");

        initializer.run(null);

        verify(userRepository, never()).delete(any(User.class));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Keycloak đang nối chưa có admin: mặc định không xoá hàng users, không tạo tài khoản Keycloak")
    void missingInKeycloakLeavesDbAlone() {
        relink(false);
        adminInDb("kc-cua-nhom");
        when(keycloakAdminClient.findUserIdByEmail(EMAIL)).thenReturn(null);

        initializer.run(null);

        verify(userRepository, never()).delete(any(User.class));
        verify(userRepository, never()).save(any(User.class));
        verify(keycloakAdminClient, never()).createUser(anyString(), anyString());
    }

    @Test
    @DisplayName("DEV_ADMIN_RELINK=true: chỉ cập nhật keycloak_id của hàng có sẵn")
    void relinkUpdatesKeycloakId() {
        relink(true);
        User admin = adminInDb("kc-cu");
        when(keycloakAdminClient.findUserIdByEmail(EMAIL)).thenReturn("kc-moi");

        initializer.run(null);

        verify(userRepository, never()).delete(any(User.class));
        verify(userRepository).save(admin);
        assertEquals("kc-moi", admin.getKeycloakId());
    }

    @Test
    @DisplayName("DEV_ADMIN_RELINK=true và Keycloak chưa có admin: tạo trên Keycloak rồi gắn vào hàng có sẵn")
    void relinkCreatesInKeycloak() {
        relink(true);
        User admin = adminInDb("kc-cu");
        when(keycloakAdminClient.findUserIdByEmail(EMAIL)).thenReturn(null);
        when(keycloakAdminClient.createUser(EMAIL, "LoadMaster Admin")).thenReturn("kc-moi");

        initializer.run(null);

        verify(userRepository, never()).delete(any(User.class));
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertSame(admin, saved.getValue());
        assertEquals("kc-moi", admin.getKeycloakId());
    }

    @Test
    @DisplayName("Cả hai chưa có: tạo admin trên Keycloak và trong DB")
    void createsWhenMissingEverywhere() {
        relink(false);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(keycloakAdminClient.findUserIdByEmail(EMAIL)).thenReturn(null);
        when(keycloakAdminClient.createUser(EMAIL, "LoadMaster Admin")).thenReturn("kc-moi");

        initializer.run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertEquals("kc-moi", saved.getValue().getKeycloakId());
        assertEquals(UserRole.SYSTEM_ADMIN, saved.getValue().getUserRoleType());
    }
}
