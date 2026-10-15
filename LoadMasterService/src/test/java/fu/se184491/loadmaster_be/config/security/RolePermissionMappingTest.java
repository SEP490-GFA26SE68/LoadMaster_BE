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
    @DisplayName("SYSTEM_MANAGER should have USERS_READ and USERS_CREATE permissions")
    void systemManager_hasUsersReadAndCreatePermissions() {
        Set<Permission> permissions = rolePermissionMapping.getPermissions(UserRole.SYSTEM_MANAGER);

        assertThat(permissions).containsExactlyInAnyOrder(
                Permission.USERS_READ,
                Permission.USERS_CREATE
        );
    }

    @Test
    @DisplayName("KeycloakRealmRoleConverter should emit ROLE_SYSTEM_MANAGER, USERS_READ, and USERS_CREATE for SYSTEM_MANAGER JWT")
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
                "USERS_READ",
                "USERS_CREATE"
        );
    }
}
