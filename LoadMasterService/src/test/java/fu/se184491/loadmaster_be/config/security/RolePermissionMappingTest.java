package fu.se184491.loadmaster_be.config.security;

import fu.se184491.loadmaster_be.constant.account.Permission;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RolePermissionMappingTest {

    private final RolePermissionMapping rolePermissionMapping = new RolePermissionMapping();
    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter(rolePermissionMapping);

    @Test
    @DisplayName("Seam 2 - SYSTEM_MANAGER should only have USERS_READ (no USERS_CREATE permission)")
    void systemManager_hasOnlyUsersReadPermission() {
        Set<Permission> permissions = rolePermissionMapping.getPermissions(UserRole.SYSTEM_MANAGER);

        assertThat(permissions).containsExactly(
                Permission.USERS_READ
        );
    }

    @Test
    @DisplayName("KeycloakRealmRoleConverter should emit ROLE_SYSTEM_MANAGER and USERS_READ for SYSTEM_MANAGER JWT")
    void converter_emitsRoleAndPermissionsForSystemManager() {
        Jwt jwt = new Jwt(
                "mock-token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("realm_access", Map.of("roles", List.of("SYSTEM_MANAGER")))
        );

        Collection<GrantedAuthority> authorities = converter.convert(jwt);
        Set<String> authorityNames = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        assertThat(authorityNames).containsExactlyInAnyOrder(
                "ROLE_SYSTEM_MANAGER",
                "USERS_READ"
        );
    }

    @Test
    @DisplayName("DISPATCHER lập yêu cầu giao: có DELIVERY_DEMAND_MANAGE")
    void dispatcher_managesDeliveryRequirements() {
        assertThat(rolePermissionMapping.getPermissions(UserRole.DISPATCHER))
                .contains(Permission.DELIVERY_DEMAND_MANAGE);
    }

    @Test
    @DisplayName("MANAGER chỉ đọc: xe, loại xe, yêu cầu giao")
    void manager_hasReadOnlyPermissions() {
        assertThat(rolePermissionMapping.getPermissions(UserRole.MANAGER)).containsExactlyInAnyOrder(
                Permission.VEHICLES_READ,
                Permission.VEHICLE_TYPES_READ,
                Permission.DELIVERY_READ
        );
    }

    @Test
    @DisplayName("ADMIN của công ty không lập chuyến")
    void admin_cannotManageTrips() {
        assertThat(rolePermissionMapping.getPermissions(UserRole.ADMIN))
                .doesNotContain(Permission.TRIP_MANAGE);
    }

    @Test
    @DisplayName("ADMIN should include USERS_DELETE permission to manage company users")
    void admin_hasUsersDeletePermission() {
        Set<Permission> permissions = rolePermissionMapping.getPermissions(UserRole.ADMIN);

        assertThat(permissions).contains(
                Permission.USERS_READ,
                Permission.USERS_CREATE,
                Permission.USERS_UPDATE,
                Permission.USERS_LOCK,
                Permission.USERS_DELETE
        );
    }
}
