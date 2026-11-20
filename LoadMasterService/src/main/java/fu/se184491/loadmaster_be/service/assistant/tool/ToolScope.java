package fu.se184491.loadmaster_be.service.assistant.tool;

import fu.se184491.loadmaster_be.constant.account.UserRole;

import java.util.EnumSet;
import java.util.Set;

/** Phạm vi công cụ của một lượt: khách, hoặc người đăng nhập với các vai trò của họ (hợp các vai trò). */
public record ToolScope(Set<UserRole> roles, boolean guest) {

    public ToolScope {
        roles = roles == null || roles.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(roles));
    }

    public static ToolScope ofGuest() {
        return new ToolScope(Set.of(), true);
    }

    public static ToolScope of(UserRole... roles) {
        return new ToolScope(Set.of(roles), false);
    }
}
