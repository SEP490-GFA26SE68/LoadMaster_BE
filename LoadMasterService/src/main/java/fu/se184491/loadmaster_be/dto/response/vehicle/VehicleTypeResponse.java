
package fu.se184491.loadmaster_be.dto.response.vehicle;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record VehicleTypeResponse(
        Long id,
        String name,

        // Kích thước thùng xe (cm)
        Integer innerLength,
        Integer innerWidth,
        Integer innerHeight,

        // Kích thước cửa xe (cm)
        Integer doorWidth,
        Integer doorHeight,

        BigDecimal maxPayloadKg,
        BigDecimal frontAxleLimitKg,
        BigDecimal rearAxleLimitKg,
        BigDecimal frontAxleX,
        BigDecimal rearAxleX,
        BigDecimal emptyFrontAxleLoadKg,
        BigDecimal emptyRearAxleLoadKg,
        BigDecimal maxCogOffsetRatio,
        BigDecimal maxCogOffsetRatioY,
        BigDecimal floorLoadCapacityKgM2,
        Boolean hazardousCapable,
        // Kiểu thân xe (tên của VehicleBodyStyle); null khi chưa chọn. Chỉ đổi hình xe trong khung 3D.
        String bodyStyle,

        List<VehicleTypeObstacleResponse> obstacles,

        Long vehicleCount
) {
}
