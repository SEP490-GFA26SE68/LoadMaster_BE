
package fu.se184491.loadmaster_be.dto.request.vehicle;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

public record CreateVehicleTypeRequest(

        @NotBlank
        String name,

        @NotNull
        @Positive
        Integer innerLength,

        @NotNull
        @Positive
        Integer innerWidth,

        @NotNull
        @Positive
        Integer innerHeight,

        // Kích thước cửa khoang hàng (cm)
        @Positive
        Integer doorWidth,

        @Positive
        Integer doorHeight,

        @NotNull
        @Positive
        BigDecimal maxPayloadKg,

        @Positive
        BigDecimal frontAxleLimitKg,

        @Positive
        BigDecimal rearAxleLimitKg,

        // HC-12 (cm, kg): vị trí hai trục theo trục X của thùng (X = 0 ở vách trước thùng hàng, trục trước được phép âm
        // khi nằm dưới cabin) và tải trên mỗi trục khi xe rỗng. Khai đủ cùng hai giới hạn tải trục ở trên, hoặc để trống cả sáu.
        BigDecimal frontAxleX,

        BigDecimal rearAxleX,

        @PositiveOrZero
        BigDecimal emptyFrontAxleLoadKg,

        @PositiveOrZero
        BigDecimal emptyRearAxleLoadKg,

        BigDecimal maxCogOffsetRatio,

        // H11: lệch trọng tâm ngang tối đa (0–0,5); trống = dùng maxCogOffsetRatio. Tuỳ chọn.
        @DecimalMin("0.0") @DecimalMax("0.5")
        BigDecimal maxCogOffsetRatioY,

        // H10: tải trọng sàn, kg/m²; trống = không kiểm tải sàn. Tuỳ chọn.
        @Positive
        BigDecimal floorLoadCapacityKgM2,

        Boolean hazardousCapable,

        @Valid
        List<CreateVehicleTypeObstacleRequest> obstacles
) {
}
