package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Một kiện đã xếp của phương án, đủ cho khung 3D.
 * Toạ độ và kích thước: cm (gốc ở góc vách trước - trái - sàn, X dọc thùng về phía cửa sau).
 * Khối lượng: kg. Kích thước đã áp hướng xoay.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlacementDetailResponse {
    private Long id;
    private Long packageId;
    private String packageCode;
    private String trackingBarcode;
    private BigDecimal weightKg;
    private BigDecimal posX;
    private BigDecimal posY;
    private BigDecimal posZ;
    private BigDecimal packedLength;
    private BigDecimal packedWidth;
    private BigDecimal packedHeight;
    private Integer rotationType;
    private Integer stepSequence;
    private Boolean pinned;
    private Long stopId;
    private String stopName;
    private Integer stopSequence;
    /**
     * Các mã hướng (0..5, như {@code rotationType}) kiện được nhận khi chỉnh tay, theo loại kiện như dịch vụ tối ưu đọc:
     * không có loại kiện hoặc tắt xoay thì chỉ hướng gốc. Luôn gồm hướng đang xếp.
     */
    private List<Integer> allowedRotations;
    /**
     * Vùng chứa tâm kiện theo X (mã ở {@code LoadPlanDetailResponse.stopZones}); khác vùng của điểm giao mình là một lần
     * dỡ-xếp lại. Null khi phương án không trả vùng.
     */
    private String stopZoneId;
    /**
     * Lý do dịch vụ tối ưu đặt kiện ở đây (mã: ZONE_STOP_2, HEAVY_LOW, PINNED, …) và bốn số đo của chỗ đặt. Null khi cờ
     * PLAN_SCORE_J tắt lúc chạy, phương án cũ, bản dựng từ chỉnh tay hay bản chạy lại.
     */
    private List<String> reasonCodes;
    /** Áp lực trên mặt trên của kiện [kg/m²]; 0 khi không có gì đè. */
    private BigDecimal stackPressureKgM2;
    /** Tải đè tối đa σ của kiện [kg/m²]; null cũng khi kiện không nhận tải. */
    private BigDecimal topLoadCapacityKgM2;
    /** Diện tích đáy được đỡ / diện tích đáy (0..1); 1 trên sàn. */
    private BigDecimal supportRatio;
    /** Khoảng cách từ mặt sau kiện tới cửa [cm]. */
    private BigDecimal distanceToDoorCm;
}
