package fu.se184491.loadmaster_be.dto.request.optimization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Duyệt bản chỉnh tay: các chỗ xếp đã dời / xoay (theo mã chỗ xếp của phương án nguồn) và tập kiện ghim của bản duyệt.
 * Toạ độ cm, gốc ở góc vách trước - trái - sàn. {@code pinnedPlacementIds} vắng là giữ ghim của phương án nguồn.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApproveEditsRequest {

    @Valid
    private List<EditedPlacement> placements;

    private List<Long> pinnedPlacementIds;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EditedPlacement {
        @NotNull(message = "VALIDATION_ERROR")
        private Long placementId;
        @NotNull(message = "VALIDATION_ERROR")
        private BigDecimal posX;
        @NotNull(message = "VALIDATION_ERROR")
        private BigDecimal posY;
        @NotNull(message = "VALIDATION_ERROR")
        private BigDecimal posZ;
        /** Mã hướng 0..5 (LWH, WLH, LHW, WHL, HLW, HWL); vắng là giữ hướng cũ. */
        private Integer rotationType;
    }
}
