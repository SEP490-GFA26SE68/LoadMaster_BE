package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripDetailResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripStopResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolPage;
import fu.se184491.loadmaster_be.service.driver.DriverService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import fu.se184491.loadmaster_be.service.trip.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Chuyến của công ty: điều phối viên, quản lý công ty thấy mọi chuyến của công ty; tài xế chỉ chuyến giao cho mình.
 * Đọc qua đúng các service của màn chuyến (lọc theo công ty của phiên); tài xế được kiểm thêm "chuyến của mình" bằng
 * {@link DriverService#requireOwnTrip}. Không đọc giám sát trực tiếp (không gọi dịch vụ chỉ đường) và không trả mã số
 * nội bộ, toạ độ hay chỗ xếp từng kiện.
 */
@Component
@RequiredArgsConstructor
public class TripsTool implements AssistantTool {

    static final int LIMIT = 20;
    /** Số phương án gần nhất đưa cho mô hình; một lần chạy ra ba phương án, cộng bản duyệt và bản chỉnh tay. */
    static final int MAX_PLANS = 6;

    private final CurrentUserService currentUser;
    private final TripService trips;
    private final TripLookupHelper lookup;
    private final LoadPlanQueryService loadPlans;
    private final DriverService drivers;

    /** Một dòng của danh sách chuyến. Giờ là giờ Việt Nam. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TripRow(String tripCode, String tripName, TripStatus status, LocalDateTime departureTime,
                          String vehicleName, String licensePlate, String driverName, Integer stopCount,
                          Integer packageCount) {
    }

    /** Chi tiết một chuyến; {@code plans} vắng với tài xế. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TripView(String tripCode, String tripName, TripStatus status, LocalDateTime departureTime,
                           String vehicleName, String licensePlate, String driverName, String originName,
                           String cancelReason, Integer packageCount, Boolean routeOptimized, List<Stop> stops,
                           Integer planCount, List<Plan> plans) {
    }

    /** Điểm theo thứ tự đi; {@code type} là DELIVERY hoặc PICKUP (nhận hàng dọc đường). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stop(Integer sequence, String name, String address, String type, DeliveryStopStatus status,
                       LocalDateTime deadline, LocalDateTime plannedArrival, LocalDateTime actualArrival) {
    }

    /**
     * Một phương án xếp hàng, mới nhất trước. {@code staleReason} luôn có mặt: máy chủ chưa lưu lý do lỗi thời, nên
     * {@code null} nghĩa là không biết lý do — mô hình không được tự đoán.
     */
    public record Plan(String label, String objective, boolean approved, String approvedBy, boolean manuallyEdited,
                       boolean stale, String staleReason, BigDecimal volumeUtilization, Integer packedItems,
                       int unplacedCount, Integer rehandlingCount) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.DISPATCHER, UserRole.MANAGER, UserRole.DRIVER);
    }

    @Tool(name = "list_trips", description = """
            Danh sách chuyến mới tạo gần nhất (tối đa 20) kèm tổng số: mã chuyến, tên, trạng thái, giờ xuất phát, \
            xe, tài xế, số điểm, số kiện. Tài xế chỉ thấy chuyến của mình. Dùng khi hỏi có những chuyến nào, bao \
            nhiêu chuyến đang ở một trạng thái.""")
    public ToolPage<TripRow> listTrips(
            @ToolParam(required = false, description = """
                    Lọc theo trạng thái, bỏ trống là mọi trạng thái: DRAFT (nháp), PLANNED (đã lập kế hoạch), \
                    LOADING (đang xếp hàng), IN_TRANSIT (đang vận chuyển), DELIVERED (đã giao), CANCELLED (đã huỷ)""")
            String status) {
        TripStatus filter = statusOf(status);
        User me = currentUser.getCurrentUser();
        Long driverId = me.getUserRoleType() == UserRole.DRIVER ? me.getId() : null;
        Page<TripResponse> page = trips.getTrips(PageRequest.of(0, LIMIT, Sort.by(Sort.Direction.DESC, "createdAt")),
                null, filter, null, null, null, driverId, false);
        List<TripRow> rows = page.getContent().stream().map(TripsTool::rowOf).toList();
        int total = (int) Math.min(page.getTotalElements(), Integer.MAX_VALUE);
        return new ToolPage<>(total, rows.size(), rows, false);
    }

    @Tool(name = "get_trip", description = """
            Chi tiết một chuyến theo mã chuyến (ví dụ TRIP-2026-0914): trạng thái, xe, tài xế, các điểm theo thứ tự \
            đi (hạn giao, giờ đến kế hoạch và giờ đến thật), và với điều phối viên, quản lý: các phương án xếp hàng \
            gần nhất (nhãn A/B/C, đã duyệt, đã chỉnh tay, lỗi thời). Tài xế chỉ xem được chuyến của mình.""")
    public TripView getTrip(@ToolParam(description = "Mã chuyến, ví dụ TRIP-2026-0914") String tripCode) {
        if (!StringUtils.hasText(tripCode)) {
            throw new AppException(ErrorCode.TRIP_NOT_FOUND);
        }
        User me = currentUser.getCurrentUser();
        boolean driver = me.getUserRoleType() == UserRole.DRIVER;
        Trip trip = driver ? drivers.requireOwnTrip(tripCode) : lookup.requireTrip(tripCode, companyOf(me));
        TripDetailResponse detail = trips.getTripDetail(trip.getId());
        List<LoadPlanDetailResponse> all = driver ? null : loadPlans.getTripLoadPlans(trip.getId()).getPlans();
        return viewOf(detail, all);
    }

    private static TripStatus statusOf(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return TripStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }
    }

    private static Long companyOf(User user) {
        if (user.getCompany() == null) {
            throw new AppException(ErrorCode.TRIP_NOT_FOUND);
        }
        return user.getCompany().getId();
    }

    private static TripRow rowOf(TripResponse trip) {
        return new TripRow(trip.tripCode(), trip.tripName(), trip.status(), trip.departureTime(), trip.vehicleName(),
                trip.licensePlate(), trip.driverName(), trip.stopCount(), trip.packageCount());
    }

    private static TripView viewOf(TripDetailResponse detail, List<LoadPlanDetailResponse> all) {
        TripResponse trip = detail.trip();
        List<Stop> stops = detail.stops() == null ? List.of() : detail.stops().stream().map(TripsTool::stopOf).toList();
        List<Plan> plans = all == null ? null : all.stream()
                .sorted(Comparator.comparing(LoadPlanDetailResponse::getId,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .limit(MAX_PLANS)
                .map(TripsTool::planOf)
                .toList();
        return new TripView(trip.tripCode(), trip.tripName(), trip.status(), trip.departureTime(), trip.vehicleName(),
                trip.licensePlate(), trip.driverName(), trip.originName(), trip.cancelReason(), trip.packageCount(),
                trip.hasActiveRoutePlan(), stops, all == null ? null : all.size(), plans);
    }

    private static Stop stopOf(TripStopResponse stop) {
        return new Stop(stop.stopSequence(), stop.stopName(), stop.address(), stop.stopType(), stop.status(),
                stop.deadline(), stop.plannedArrival(), stop.actualArrival());
    }

    private static Plan planOf(LoadPlanDetailResponse plan) {
        return new Plan(plan.getCandidateLabel(), plan.getObjective(), Boolean.TRUE.equals(plan.getApproved()),
                plan.getApprovedByName(), Boolean.TRUE.equals(plan.getManuallyEdited()),
                Boolean.TRUE.equals(plan.getStale()), null, plan.getVolumeUtilization(), plan.getPackedItemsCount(),
                plan.getUnplaced() == null ? 0 : plan.getUnplaced().size(), plan.getRehandlingCount());
    }
}
