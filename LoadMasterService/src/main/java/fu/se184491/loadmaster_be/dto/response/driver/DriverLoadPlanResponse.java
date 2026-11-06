package fu.se184491.loadmaster_be.dto.response.driver;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Phương án kho đã xếp lên xe của chuyến, cho khung 3D "Xem vị trí hàng" của tài xế ({@code GET /api/driver/trips/{id}/load-plan}).
 *
 * <p>Lòng thùng và cửa (cm) lấy từ loại xe đang gắn; null khi chưa khai. Chỉ kiện kho đã xác nhận xếp hoặc đã ghi sai lệch; kiện
 * ghi sai lệch đứng ở vị trí thật kho ghi ({@code deviated}). Toạ độ, kích thước: cm, đã áp hướng xoay.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverLoadPlanResponse {

    private Long tripId;
    private String tripCode;
    private Long loadPlanId;
    private String licensePlate;
    private Integer innerLength;
    private Integer innerWidth;
    private Integer innerHeight;
    private Integer doorWidth;
    private Integer doorHeight;
    private BigDecimal maxPayloadKg;
    private List<Stop> stops;
    private List<Placement> placements;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Stop {
        private Long stopId;
        private Integer stopSequence;
        private String stopName;
        /** {@code PENDING}, {@code ARRIVED} hoặc {@code COMPLETED}. */
        private String status;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Placement {
        private Long placementId;
        private Long packageId;
        private String packageCode;
        private String trackingBarcode;
        private String packageName;
        private Integer posX;
        private Integer posY;
        private Integer posZ;
        private Integer packedLength;
        private Integer packedWidth;
        private Integer packedHeight;
        private Integer rotation;
        private BigDecimal weightKg;
        /** Thứ tự xếp. */
        private Integer stepSequence;
        /** Thứ tự dỡ của cả chuyến: điểm giao trước dỡ trước; trong một điểm, kiện xếp sau dỡ trước. */
        private Integer unloadOrder;
        private Long stopId;
        private Integer stopSequence;
        /** Kho ghi sai lệch: toạ độ là vị trí thật kho ghi. */
        private Boolean deviated;
        /** Đã dỡ khỏi xe. */
        private Boolean unloaded;
    }
}
