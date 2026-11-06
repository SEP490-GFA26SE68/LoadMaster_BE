package fu.se184491.loadmaster_be.dto.response.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Kết quả báo kiện hỏng lúc xếp. {@code outcome}: {@code LEFT_OUT} — kiện bị bỏ lại kho, kho xếp tiếp; {@code REPLANNED} — chuyến về Đã
 * lập kế hoạch ({@code tripStatus} là {@code PLANNED}), phiên xếp dừng.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DamageResponse {

    private Long placementId;
    private Long packageId;
    private String outcome;
    private int restingCount;
    private String tripStatus;
    private PlacementStepResponse nextPlacement;
}
