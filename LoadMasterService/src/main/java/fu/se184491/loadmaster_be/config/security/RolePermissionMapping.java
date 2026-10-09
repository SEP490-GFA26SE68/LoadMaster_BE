package fu.se184491.loadmaster_be.config.security;

import fu.se184491.loadmaster_be.constant.account.Permission;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import org.springframework.security.core.parameters.P;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@Component
public class RolePermissionMapping {

    private final Map<UserRole, Set<Permission>> mappings;

    public RolePermissionMapping() {
        Map<UserRole, Set<Permission>> map =
                new EnumMap<>(UserRole.class);

        map.put(
                UserRole.SYSTEM_ADMIN,
                Set.of(
                        Permission.USERS_READ,
                        Permission.USERS_CREATE,
                        Permission.USERS_UPDATE,
                        Permission.USERS_LOCK,
                        Permission.USERS_DELETE,
                        Permission.AUDIT_READ
                )
        );

        map.put(
                UserRole.SYSTEM_MANAGER,
                Set.of()
        );

        map.put(
                UserRole.SYSTEM_SUPPORTER,
                Set.of()
        );

        map.put(
                UserRole.ADMIN,
                Set.of(
                        Permission.USERS_READ,
                        Permission.USERS_CREATE,
                        Permission.USERS_UPDATE,
                        Permission.USERS_LOCK,
                        Permission.AUDIT_READ
                )
        );

        map.put(
                UserRole.MANAGER,
                Set.of()
        );

        map.put(
                UserRole.DISPATCHER,
                Set.of(
                        Permission.VEHICLE_TYPES_CREATE,
                        Permission.VEHICLE_TYPES_DELETE,
                        Permission.VEHICLE_TYPES_READ,
                        Permission.VEHICLE_TYPES_UPDATE,
                        Permission.VEHICLES_CREATE,
                        Permission.VEHICLES_DELETE,
                        Permission.VEHICLES_READ,
                        Permission.VEHICLES_UPDATE
                )
        );

        map.put(
                UserRole.WAREHOUSE_WORKER,
                Set.of()
        );

        map.put(
                UserRole.DRIVER,
                Set.of()
        );

        this.mappings =
                Collections.unmodifiableMap(map);
    }

    public Set<Permission> getPermissions(UserRole role) {
        return mappings.getOrDefault(
                role,
                Set.of()
        );
    }
}