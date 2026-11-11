package fu.se184491.loadmaster_be.service.notification;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Ai nhận thông báo nào — luật thuần, không đọc DB. Thông báo là dòng nhật ký ({@code audit_logs}):
 * máy chủ không có bảng thông báo riêng, mỗi vai trò đọc đúng những loại việc nó cần biết, trừ việc
 * chính mình làm. Phạm vi công ty do {@link NotificationService} lọc trước khi gọi vào đây.
 *
 * <ul>
 *   <li>Quản trị hệ thống: việc trên tài khoản của mọi công ty.</li>
 *   <li>Quản trị công ty: việc trên tài khoản của công ty mình, sắp hết credit.</li>
 *   <li>Quản lý công ty: chuyến hoàn thành, bị huỷ, sự cố giao, sự cố cấp chuyến chuyển lên quản lý.</li>
 *   <li>Điều phối viên: đồng nghiệp duyệt phương án, kho xếp xong, sự cố giao, nguy cơ trễ hạn, chuyến hoàn thành,
 *       bị huỷ, xác nhận tay chờ duyệt, tài xế báo sự cố, sự cố chuyển quản lý, quản lý đã nhập hạn mới, yêu cầu
 *       nhận hàng dọc đường mới gửi.</li>
 *   <li>Nhân viên kho: chuyến bị huỷ lúc đang xếp hàng.</li>
 *   <li>Tài xế: việc của chuyến mình từ lúc kho bắt đầu xếp (kho bắt đầu xếp, xếp xong, chuyến bị huỷ lúc đang
 *       xếp, đổi tài xế khi chuyến đã ở kho), yêu cầu nhận dọc đường của chuyến mình được duyệt / bị từ chối, và xác
 *       nhận tay của chính mình bị từ chối.</li>
 *   <li>Mọi vai trò công ty: yêu cầu hỗ trợ của chính mình được hỗ trợ khách hàng cập nhật.</li>
 * </ul>
 * Sự kiện của luồng mới thêm vào {@link #ACTIONS} và {@link #concerns} ở việc của luồng đó.
 */
public final class NotificationRules {

    public static final int WINDOW_DAYS = 7;
    public static final int LIMIT = 20;

    static final String TICKET_UPDATED = "SUPPORT_TICKET_UPDATED";
    static final String SHORTAGE_RESOLVED = "STAGING_SHORTAGE_RESOLVED";
    static final String DAMAGE_REPORTED = "LOADING_DAMAGE_REPORTED";

    private static final List<String> ACCOUNT = List.of(
            "USER_CREATED", "USER_UPDATED", "USER_LOCKED", "USER_UNLOCKED", "USER_STATUS_CHANGED",
            "USER_PASSWORD_RESET", "USER_DELETED");

    private static final Map<UserRole, Set<String>> ACTIONS = new EnumMap<>(UserRole.class);

    static {
        ACTIONS.put(UserRole.SYSTEM_ADMIN, Set.copyOf(ACCOUNT));
        ACTIONS.put(UserRole.ADMIN, union(ACCOUNT, List.of("CREDIT_LOW_BALANCE", TICKET_UPDATED)));
        ACTIONS.put(UserRole.MANAGER, Set.of(
                "TRIP_COMPLETED", "DELIVERY_ISSUE_REPORTED", "TRIP_CANCELLED", "TRIP_EXCEPTION_ESCALATED", TICKET_UPDATED));
        ACTIONS.put(UserRole.DISPATCHER, Set.of(
                "PLAN_APPROVED", "LOADING_COMPLETED", "DELIVERY_ISSUE_REPORTED", "DELIVERY_ETA_RISK", "TRIP_COMPLETED",
                "TRIP_CANCELLED", "MANUAL_CONFIRM_REQUESTED", "TRIP_EXCEPTION_REPORTED", "TRIP_EXCEPTION_ESCALATED",
                "DEADLINE_RENEGOTIATED", "PICKUP_REQUESTED", TICKET_UPDATED));
        ACTIONS.put(UserRole.WAREHOUSE_WORKER, Set.of("TRIP_CANCELLED", TICKET_UPDATED));
        ACTIONS.put(UserRole.DRIVER, Set.of(
                "MANUAL_CONFIRM_REJECTED", "TRIP_STATUS_TRANSITION", "LOADING_COMPLETED", "TRIP_CANCELLED", "TRIP_UPDATED",
                "PICKUP_APPROVED", "PICKUP_REJECTED",
                TICKET_UPDATED));
    }

    // Kho báo thiếu lúc soạn, kiện hỏng lúc xếp, tìm thấy lại kiện, và quyết định của điều phối viên với kiện báo thiếu
    static {
        extend(UserRole.DISPATCHER, "STAGING_SHORTAGE_REPORTED", DAMAGE_REPORTED, "PACKAGE_FOUND");
        extend(UserRole.MANAGER, SHORTAGE_RESOLVED, DAMAGE_REPORTED);
        extend(UserRole.WAREHOUSE_WORKER, SHORTAGE_RESOLVED);
        extend(UserRole.DRIVER, SHORTAGE_RESOLVED, DAMAGE_REPORTED);
    }

    /** Pha mà tài xế đã thấy chuyến ở "Chuyến của tôi": từ lúc kho bắt đầu xếp. */
    private static final Set<String> DRIVER_KNOWS = Set.of(TripStatus.LOADING.name(), TripStatus.IN_TRANSIT.name());

    private NotificationRules() {
    }

    /**
     * Một dòng nhật ký như luật cần đọc. {@code tripDriverId}: tài xế hiện tại của chuyến mà dòng nói tới — chỉ dùng
     * khi dòng không ghi tài xế lúc xảy ra ({@code driverId} trong giá trị mới).
     */
    public record Event(String action, Long actorId, Map<String, Object> oldValues, Map<String, Object> newValues,
                        Long tripDriverId) {
    }

    public record Viewer(Long id, UserRole role) {
    }

    /** Loại việc vai trò nhận; rỗng khi vai trò không có thông báo nào. */
    public static Set<String> actionsFor(UserRole role) {
        return role == null ? Set.of() : ACTIONS.getOrDefault(role, Set.of());
    }

    /** Dòng có đáng báo cho người xem không: đúng loại của vai trò, không phải việc chính mình làm, và luật theo người. */
    public static boolean concerns(Event event, Viewer viewer) {
        if (!actionsFor(viewer.role()).contains(event.action())) {
            return false;
        }
        if (event.actorId() != null && event.actorId().equals(viewer.id())) {
            return false;
        }
        String action = event.action();
        // Cập nhật yêu cầu hỗ trợ chỉ báo cho người đã gửi yêu cầu
        if (TICKET_UPDATED.equals(action)) {
            return sameId(value(event.newValues(), "requestedBy"), viewer.id());
        }
        return switch (viewer.role()) {
            case DRIVER -> concernsDriver(event, viewer.id());
            // Kho: quyết định với kiện chính mình báo thiếu; chuyến bị huỷ lúc đang xếp (dỡ phần đã xếp)
            case WAREHOUSE_WORKER -> SHORTAGE_RESOLVED.equals(action)
                    ? sameId(value(event.newValues(), "reportedBy"), viewer.id())
                    : wasLoading(event);
            // Quản lý: kiện bị bỏ khỏi chuyến (yêu cầu giao thành giao thiếu), không phải "tìm tiếp"
            case MANAGER -> !SHORTAGE_RESOLVED.equals(action) || dropped(event);
            default -> true;
        };
    }

    private static boolean concernsDriver(Event event, Long driverId) {
        return switch (event.action()) {
            case "MANUAL_CONFIRM_REJECTED" -> sameId(value(event.newValues(), "requestedBy"), driverId);
            // Đổi tài xế khi chuyến đã ở kho: báo người mới và người vừa bị thay
            case "TRIP_UPDATED" -> driverKnows(text(value(event.newValues(), "tripStatus")))
                    && (sameId(value(event.newValues(), "driverId"), driverId)
                    || sameId(value(event.oldValues(), "driverId"), driverId));
            // Kho bắt đầu xếp: chuyến mới của tài xế
            case "TRIP_STATUS_TRANSITION" -> TripStatus.LOADING.name().equals(text(value(event.newValues(), "status")))
                    && sameId(driverOf(event), driverId);
            case "LOADING_COMPLETED" -> sameId(driverOf(event), driverId);
            case "TRIP_CANCELLED" -> wasLoading(event) && sameId(driverOf(event), driverId);
            // Quyết định về yêu cầu nhận dọc đường: tài xế của chuyến lúc quyết định
            case "PICKUP_APPROVED", "PICKUP_REJECTED" -> sameId(value(event.newValues(), "driverId"), driverId);
            // Chuyến tạm dừng chờ điều phối lập lại phương án: kiện hỏng có kiện tựa lên, kiện báo thiếu bị bỏ
            case DAMAGE_REPORTED -> "REPLANNED".equals(text(value(event.newValues(), "outcome")))
                    && sameId(driverOf(event), driverId);
            case SHORTAGE_RESOLVED -> dropped(event) && sameId(driverOf(event), driverId);
            default -> false;
        };
    }

    // Set.of ném NullPointerException khi hỏi null: sự kiện sửa chuyến không mang trạng thái thì tài xế chưa biết chuyến
    private static boolean driverKnows(String status) {
        return status != null && DRIVER_KNOWS.contains(status);
    }

    private static boolean dropped(Event event) {
        return "DROP".equals(text(value(event.newValues(), "decision")));
    }

    private static void extend(UserRole role, String... actions) {
        Set<String> merged = new HashSet<>(ACTIONS.getOrDefault(role, Set.of()));
        merged.addAll(List.of(actions));
        ACTIONS.put(role, Set.copyOf(merged));
    }

    private static boolean wasLoading(Event event) {
        return !"TRIP_CANCELLED".equals(event.action())
                || TripStatus.LOADING.name().equals(text(value(event.oldValues(), "status")));
    }

    private static Object driverOf(Event event) {
        Object recorded = value(event.newValues(), "driverId");
        return recorded != null ? recorded : event.tripDriverId();
    }

    private static Object value(Map<String, Object> values, String key) {
        return values == null ? null : values.get(key);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean sameId(Object value, Long id) {
        if (value == null || id == null) {
            return false;
        }
        return Objects.equals(value.toString().trim(), id.toString());
    }

    private static Set<String> union(List<String> first, List<String> second) {
        return Set.copyOf(java.util.stream.Stream.concat(first.stream(), second.stream()).toList());
    }
}
