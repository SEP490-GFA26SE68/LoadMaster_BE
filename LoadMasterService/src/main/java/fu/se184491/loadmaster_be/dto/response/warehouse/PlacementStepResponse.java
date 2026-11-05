package fu.se184491.loadmaster_be.dto.response.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một bước xếp của phiên xếp. Toạ độ và kích thước: cm, đã áp hướng xoay.
 *
 * <p>{@code qrToken}, {@code packageCode}, {@code trackingBarcode} để kho đối chiếu kiện bằng quét
 * hoặc gõ mã; {@code stopId} / {@code stopSequence} / {@code stopName} là điểm giao thật của kiện
 * (qua yêu cầu giao). {@code status}: {@code CONFIRMED} (đã xác nhận), {@code DEVIATION} (đã ghi sai
 * lệch) hoặc {@code PENDING} (chưa xử lý).</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlacementStepResponse {

    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_DEVIATION = "DEVIATION";
    public static final String STATUS_PENDING = "PENDING";

    private Long placementId;
    private Long packageId;
    private String packageName;
    private String packageCode;
    private String trackingBarcode;
    private String qrToken;
    private Integer posX;
    private Integer posY;
    private Integer posZ;
    private Integer packedLength;
    private Integer packedWidth;
    private Integer packedHeight;
    private Integer rotation;
    private Integer sequence;
    private Long stopId;
    private Integer stopSequence;
    private String stopName;
    private String status;
}
