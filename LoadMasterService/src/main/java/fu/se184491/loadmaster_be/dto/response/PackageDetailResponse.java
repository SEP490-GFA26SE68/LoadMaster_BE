package fu.se184491.loadmaster_be.dto.response;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import lombok.Builder;

import java.math.BigDecimal;

/**
 * Response returned when scanning a package QR code.
 * Contains key package details visible to DISPATCHER and WAREHOUSE_WORKER.
 */
@Builder
public record PackageDetailResponse(
        Long id,
        String packageCode,
        String qrToken,
        HandlingClass handlingClass,
        BigDecimal actualWeightKg,
        PackageStatus status,
        Long orderId,
        TripRef trip,
        StopRef stop
) {
    /** Trip currently holding the package. {@code name} is the trip code. */
    public record TripRef(Long id, String name) {}

    /** Delivery stop of the package. {@code number} is the 1-based stop sequence. */
    public record StopRef(Integer number, String name) {}
}
