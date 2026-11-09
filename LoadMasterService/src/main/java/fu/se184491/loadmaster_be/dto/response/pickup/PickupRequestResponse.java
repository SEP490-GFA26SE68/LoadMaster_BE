package fu.se184491.loadmaster_be.dto.response.pickup;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequest;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequestPackage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Một yêu cầu nhận hàng dọc đường. {@code validationResults}: kết quả mười luật lần kiểm gần nhất ({@code rule}, {@code passed},
 * {@code code}, {@code params}); {@code layout}: chỗ xếp kiện nhận vào phần thùng trống, lưu lúc duyệt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PickupRequestResponse {

    private Long id;
    private Long tripId;
    private String tripCode;
    private String status;
    private Point pickup;
    private Point delivery;
    private LocalDateTime deadline;
    private List<Package> packages;
    private List<Map<String, Object>> validationResults;
    private LocalDateTime validatedAt;
    private String overrideReason;
    private String rejectReason;
    private Long createdById;
    private String createdByName;
    private LocalDateTime createdAt;
    private Long approvedById;
    private String approvedByName;
    private LocalDateTime approvedAt;
    private Long rejectedById;
    private String rejectedByName;
    private LocalDateTime rejectedAt;
    private Long pickupStopId;
    private Long deliveryStopId;
    private Map<String, Object> layout;
    private LocalDateTime loadedAt;
    private LocalDateTime deliveredAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Point {
        private String name;
        private String address;
        private BigDecimal latitude;
        private BigDecimal longitude;
    }

    /** Một kiện của yêu cầu; {@code cargoPackageId}, {@code qrToken}… có từ lúc duyệt (kiện đã tạo trong kho kiện). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Package {
        private Integer lineIndex;
        private String packageCode;
        private BigDecimal lengthCm;
        private BigDecimal widthCm;
        private BigDecimal heightCm;
        private BigDecimal weightKg;
        private String handlingClass;
        private Long cargoPackageId;
        private String warehouseCode;
        private String trackingBarcode;
        private String qrToken;
        private LocalDateTime pickedAt;
    }

    public static PickupRequestResponse fromEntity(PickupRequest request) {
        return PickupRequestResponse.builder()
                .id(request.getId())
                .tripId(request.getTrip() != null ? request.getTrip().getId() : null)
                .tripCode(request.getTrip() != null ? request.getTrip().getTripCode() : null)
                .status(request.getStatus() != null ? request.getStatus().name() : null)
                .pickup(new Point(request.getPickupName(), request.getPickupAddress(), request.getPickupLatitude(),
                        request.getPickupLongitude()))
                .delivery(new Point(request.getDeliveryName(), request.getDeliveryAddress(),
                        request.getDeliveryLatitude(), request.getDeliveryLongitude()))
                .deadline(request.getDeadline())
                .packages(request.getPackages().stream().map(PickupRequestResponse::packageOf).toList())
                .validationResults(request.getValidationResults() != null ? request.getValidationResults() : List.of())
                .validatedAt(request.getValidatedAt())
                .overrideReason(request.getOverrideReason())
                .rejectReason(request.getRejectReason())
                .createdById(idOf(request.getCreatedBy()))
                .createdByName(nameOf(request.getCreatedBy()))
                .createdAt(request.getCreatedAt())
                .approvedById(idOf(request.getApprovedBy()))
                .approvedByName(nameOf(request.getApprovedBy()))
                .approvedAt(request.getApprovedAt())
                .rejectedById(idOf(request.getRejectedBy()))
                .rejectedByName(nameOf(request.getRejectedBy()))
                .rejectedAt(request.getRejectedAt())
                .pickupStopId(request.getPickupStop() != null ? request.getPickupStop().getId() : null)
                .deliveryStopId(request.getDeliveryStop() != null ? request.getDeliveryStop().getId() : null)
                .layout(request.getLayout())
                .loadedAt(request.getLoadedAt())
                .deliveredAt(request.getDeliveredAt())
                .build();
    }

    private static Package packageOf(PickupRequestPackage line) {
        CargoPackage cargo = line.getCargoPackage();
        return Package.builder()
                .lineIndex(line.getLineIndex())
                .packageCode(line.getPackageCode())
                .lengthCm(line.getLengthCm())
                .widthCm(line.getWidthCm())
                .heightCm(line.getHeightCm())
                .weightKg(line.getWeightKg())
                .handlingClass(line.getHandlingClass() != null ? line.getHandlingClass().name() : null)
                .cargoPackageId(cargo != null ? cargo.getId() : null)
                .warehouseCode(cargo != null ? cargo.getPackageCode() : null)
                .trackingBarcode(cargo != null ? cargo.getTrackingBarcode() : null)
                .qrToken(cargo != null ? cargo.getQrToken() : null)
                .pickedAt(line.getPickedAt())
                .build();
    }

    private static Long idOf(User user) {
        return user != null ? user.getId() : null;
    }

    private static String nameOf(User user) {
        return user != null ? user.getFullName() : null;
    }
}
