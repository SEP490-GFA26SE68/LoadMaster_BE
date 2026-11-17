package fu.se184491.loadmaster_be.dto.response.driver;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverTripDetailResponse {

    private Long tripId;
    private String tripCode;
    private String tripName;
    private String status;
    private LocalDateTime departureTime;
    private LocalDateTime completedAt;
    private Boolean loadingCompleted;

    // Kho xuất phát (Origin / Depot)
    private String depotName;
    private String depotAddress;
    private BigDecimal depotLat;
    private BigDecimal depotLng;

    // Thông tin xe
    private Long vehicleId;
    private String vehicleName;
    private String licensePlate;
    private BigDecimal maxPayloadKg;
    private String vehicleTypeName;

    // Người điều phối phụ trách
    private String dispatcherName;
    private String dispatcherPhone;

    // Lộ trình & Thống kê tóm tắt
    private Integer totalDistanceMeters;
    private Integer totalDurationSeconds;
    private Integer totalStops;
    private Integer totalPackages;
    private BigDecimal totalWeightKg;

    // Danh sách điểm dừng
    private List<StopDetail> stops;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StopDetail {
        private Long stopId;
        private Integer stopSequence;
        private String stopName;
        private String address;
        private BigDecimal lat;
        private BigDecimal lng;
        private String status;
        private String stopType;
        private LocalDateTime plannedArrival;
        private LocalDateTime actualArrival;
        private LocalDateTime deadline;
        private String plannedStatus;
        private LocalDateTime eta;
        private String etaStatus;

        // Thông tin người nhận hàng
        private String contactName;
        private String contactPhone;

        // Danh sách kiện hàng tại điểm dừng
        private List<PackageDetail> packages;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PackageDetail {
        private Long packageId;
        private String packageCode;
        private String trackingBarcode;
        private String packageName;
        private BigDecimal weightKg;
        private String status;
        private Integer unloadOrder;
    }
}
