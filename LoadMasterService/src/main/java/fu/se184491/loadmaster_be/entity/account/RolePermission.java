package fu.se184491.loadmaster_be.entity.account;

import fu.se184491.loadmaster_be.entity.account.Permission;
import fu.se184491.loadmaster_be.entity.account.Role;
import fu.se184491.loadmaster_be.entity.account.RolePermissionId;


import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "role_permissions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RolePermission {
    @EmbeddedId
    private RolePermissionId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("roleId")
    @JoinColumn(name = "role_id")
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("permissionId")
    @JoinColumn(name = "permission_id")
    private Permission permission;
}
