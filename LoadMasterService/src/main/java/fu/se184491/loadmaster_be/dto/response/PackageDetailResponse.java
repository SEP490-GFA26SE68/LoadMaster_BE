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
        Long orderId
) {}
