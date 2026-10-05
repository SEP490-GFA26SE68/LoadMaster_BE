package fu.se184491.loadmaster_be.config.security;

import fu.se184491.loadmaster_be.constant.account.Permission;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class KeycloakRealmRoleConverter
        implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final RolePermissionMapping rolePermissionMapping;

    public KeycloakRealmRoleConverter(
            RolePermissionMapping rolePermissionMapping
    ) {
        this.rolePermissionMapping = rolePermissionMapping;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {

        Map<String, Object> realmAccess =
                jwt.getClaimAsMap("realm_access");

        if (realmAccess == null) {
            return List.of();
        }

        Object rolesObject =
                realmAccess.get("roles");

        if (!(rolesObject instanceof Collection<?> roles)) {
            return List.of();
        }

        List<GrantedAuthority> authorities =
                new ArrayList<>();

        for (Object roleObject : roles) {

            String roleName =
                    roleObject.toString();

            // Giữ role authority hiện tại
            authorities.add(
                    new SimpleGrantedAuthority(
                            "ROLE_" + roleName
                    )
            );

            // Nếu role này thuộc enum của hệ thống
            try {
                UserRole userRole =
                        UserRole.valueOf(roleName);

                Set<Permission> permissions =
                        rolePermissionMapping
                                .getPermissions(userRole);

                permissions.stream()
                        .map(Permission::name)
                        .map(SimpleGrantedAuthority::new)
                        .forEach(authorities::add);

            } catch (IllegalArgumentException ignored) {
                // Keycloak có thể có role built-in như:
                // offline_access
                // uma_authorization
                // ...
                // Không phải UserRole của LoadMaster thì bỏ qua permission mapping.
            }
        }

        return authorities;
    }
}