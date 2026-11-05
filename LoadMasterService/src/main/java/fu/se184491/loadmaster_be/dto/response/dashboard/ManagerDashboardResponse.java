package fu.se184491.loadmaster_be.dto.response.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Số liệu bảng điều khiển của quản lý công ty trong một kỳ. Số không có nguồn là {@code null}, không phải 0.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagerDashboardResponse {

    private LocalDate from;
    private LocalDate to;

    /** Chuyến có giờ xuất phát trong kỳ, gồm chuyến đã huỷ. */
    private long tripCount;
    private long completedCount;
    private long cancelledCount;
    /** Đủ sáu trạng thái của chuyến, kể cả trạng thái không có chuyến nào (0). */
    private Map<String, Long> tripsByStatus;

    private Fill fill;
    /** Tổng khối lượng kiện Đã giao của các chuyến trong kỳ. */
    private BigDecimal deliveredWeightKg;
    private Delivery delivery;
    /** Đội xe lúc đọc, không theo kỳ. */
    private Vehicles vehicles;

    private List<DayFill> fillByDay;
    private List<VehicleWeight> weightByVehicle;
    private List<RecentTrip> recentTrips;

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Fill {
        /** Trung bình tỷ lệ thể tích (%) của phương án đã duyệt, chuyến không huỷ; null khi planCount = 0. */
        private BigDecimal averagePercent;
        private long planCount;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Delivery {
        /** cleanItems / finishedItems (%); null khi finishedItems = 0. */
        private BigDecimal cleanPercent;
        private long cleanItems;
        private long finishedItems;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Vehicles {
        private long total;
        /** Đang phục vụ chuyến ({@code IN_TRIP}). */
        private long inService;
        private long available;
        private long maintenance;
        private long retired;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DayFill {
        private LocalDate date;
        private BigDecimal averagePercent;
        private long planCount;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VehicleWeight {
        private Long vehicleId;
        private String vehicleName;
        private long tripCount;
        private BigDecimal deliveredWeightKg;
        /** null khi xe không có phương án đã duyệt nào trong kỳ. */
        private BigDecimal averageFillPercent;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecentTrip {
        private Long tripId;
        private String tripCode;
        private String tripName;
        /** Giờ xuất phát theo giờ Việt Nam, kèm múi giờ. */
        private OffsetDateTime departureTime;
        private String status;
        private String vehicleName;
        private String driverName;
        /** Tỷ lệ thể tích (%) của phương án đã duyệt; null khi chưa có. */
        private BigDecimal fillPercent;
    }
}
