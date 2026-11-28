package fu.se184491.loadmaster_be.service.assistant.tool;

import fu.se184491.loadmaster_be.constant.account.UserRole;

import java.util.EnumSet;
import java.util.Set;

/**
 * Phạm vi công cụ của một lượt: khách, hoặc người đăng nhập với các vai trò của họ (hợp các vai trò). {@code viewer}: lượt
 * hỏi từ khung 3D có mã phương án (công cụ điều khiển khung 3D chỉ có khi đó); khách không bao giờ có.
 */
public record ToolScope(Set<UserRole> roles, boolean guest, boolean viewer) {

    public ToolScope {
        roles = roles == null || roles.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(roles));
        viewer = viewer && !guest;
    }

    public ToolScope(Set<UserRole> roles, boolean guest) {
        this(roles, guest, false);
    }

    public static ToolScope ofGuest() {
        return new ToolScope(Set.of(), true);
    }

    public static ToolScope of(UserRole... roles) {
        return new ToolScope(Set.of(roles), false);
    }

    /** Cùng vai trò, hỏi từ khung 3D. */
    public ToolScope withViewer() {
        return new ToolScope(roles, guest, true);
    }
}
