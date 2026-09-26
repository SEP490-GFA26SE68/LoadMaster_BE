package fu.se184491.loadmaster_be.service.optimization.Impl;

import fu.se184491.loadmaster_be.dto.response.optimization.ValidationResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.optimization.CargoPackageForTripRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.optimization.TripValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements all validation rules defined in FR-OPT-01 / S3-01.
 *
 * Rules:
 * 1. Trip must exist.
 * 2. Trip must have a vehicle assigned.
 * 3. Trip must have at least 1 package (via DeliveryStop → Order → Package).
 * 4. Total actualWeightKg ≤ vehicleType.maxPayloadKg.
 * 5. Total volume (L×W×H) ≤ innerLength × innerWidth × innerHeight.
 * 6. Each package must fit at least 1 allowed rotation inside the vehicle.
 *
 * Warnings (canOptimize remains true):
 * W1. Total weight > 90% of maxPayloadKg.
 */
@Service
@RequiredArgsConstructor
public class TripValidationServiceImpl implements TripValidationService {

    /**
     * Warning threshold: if total weight exceeds this fraction of payload, emit a warning.
     */
    private static final double WEIGHT_WARNING_THRESHOLD = 0.90;

    private final TripRepository tripRepository;
    private final CargoPackageForTripRepository cargoPackageForTripRepository;

    @Override
    public ValidationResponse validate(Long tripId) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // ── Rule 1: Trip must exist ───────────────────────────────────────────
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));

        // ── Rule 2: Vehicle must be assigned ─────────────────────────────────
        Vehicle vehicle = trip.getVehicle();
        if (vehicle == null || vehicle.getVehicleType() == null) {
            errors.add("Chuyến đi chưa được gán phương tiện vận tải (vehicle).");
            return buildResult(errors, warnings);
        }

        VehicleType vt = vehicle.getVehicleType();
        long vehicleVolumeMm3 = (long) vt.getInnerLength() * vt.getInnerWidth() * vt.getInnerHeight();

        // ── Rule 3: At least 1 package ───────────────────────────────────────
        List<CargoPackage> packages = cargoPackageForTripRepository.findPackagesByTripId(tripId);
        if (packages.isEmpty()) {
            errors.add("Chuyến đi không có kiện hàng nào để tối ưu.");
            return buildResult(errors, warnings);
        }

        // ── Rules 4, 5, 6 — iterate packages ─────────────────────────────────
        BigDecimal totalWeight = BigDecimal.ZERO;
        long totalVolumeMm3 = 0;

        for (CargoPackage pkg : packages) {
            BigDecimal w = pkg.getActualWeightKg() != null ? pkg.getActualWeightKg() : BigDecimal.ZERO;
            totalWeight = totalWeight.add(w);

            PackageType pt = pkg.getPackageType();
            if (pt != null) {
                totalVolumeMm3 += (long) pt.getLength() * pt.getWidth() * pt.getHeight();

                // Rule 6: at least one rotation must fit inside the vehicle's inner dimensions
                if (!canFitAtLeastOneRotation(pt, vt)) {
                    errors.add(String.format(
                            "Kiện hàng (packageTypeId=%d) không thể xếp vào xe ở bất kỳ hướng xoay nào cho phép. "
                                    + "Kiểm tra kích thước và ràng buộc rotation.",
                            pt.getId()));
                }
            }
        }

        // Rule 4: total weight
        if (vt.getMaxPayloadKg() != null && totalWeight.compareTo(vt.getMaxPayloadKg()) > 0) {
            errors.add(String.format(
                    "Tổng trọng lượng hàng hóa (%.2f kg) vượt quá tải trọng tối đa của xe (%.2f kg).",
                    totalWeight, vt.getMaxPayloadKg()));
        }

        // Rule 5: total volume
        if (totalVolumeMm3 > vehicleVolumeMm3) {
            errors.add(String.format(
                    "Tổng thể tích hàng hóa (%d mm³) vượt quá thể tích thùng xe (%d mm³).",
                    totalVolumeMm3, vehicleVolumeMm3));
        }

        // Warning W1: weight > 90% of payload
        if (vt.getMaxPayloadKg() != null && errors.isEmpty()) {
            double ratio = totalWeight.doubleValue() / vt.getMaxPayloadKg().doubleValue();
            if (ratio > WEIGHT_WARNING_THRESHOLD) {
                warnings.add(String.format(
                        "Tải trọng hàng hóa đang ở mức %.0f%% công suất tối đa. "
                                + "Có thể gặp khó khăn khi tối ưu xếp hàng.",
                        ratio * 100));
            }
        }

        return buildResult(errors, warnings);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Checks whether at least one of the 6 standard rotation types (filtered by
     * allowRotateX/Y/Z) can fit within the vehicle's inner dimensions.
     *
     * Dimensions are in mm. A rotation is valid if the package's oriented
     * length ≤ innerLength AND width ≤ innerWidth AND height ≤ innerHeight.
     */
    private boolean canFitAtLeastOneRotation(PackageType pt, VehicleType vt) {
        int l = pt.getLength(), w = pt.getWidth(), h = pt.getHeight();
        int il = vt.getInnerLength(), iw = vt.getInnerWidth(), ih = vt.getInnerHeight();

        boolean rotX = Boolean.TRUE.equals(pt.getAllowRotateX());
        boolean rotY = Boolean.TRUE.equals(pt.getAllowRotateY());
        boolean rotZ = Boolean.TRUE.equals(pt.getAllowRotateZ());

        // Rotation 0: L×W×H (base orientation — no rotation needed)
        if (fits(l, w, h, il, iw, ih))
            return true;

        // Rotation 1: W×L×H (rotate Z)
        if (rotZ && fits(w, l, h, il, iw, ih))
            return true;

        // Rotation 2: L×H×W (rotate X)
        if (rotX && fits(l, h, w, il, iw, ih))
            return true;

        // Rotation 3: H×L×W (rotate X + Z)
        if (rotX && rotZ && fits(h, l, w, il, iw, ih))
            return true;

        // Rotation 4: W×H×L (rotate Y)
        if (rotY && fits(w, h, l, il, iw, ih))
            return true;

        // Rotation 5: H×W×L (rotate Y + Z)
        if (rotY && rotZ && fits(h, w, l, il, iw, ih))
            return true;

        return false;
    }

    /** Returns true if package oriented as (pl×pw×ph) fits inside (il×iw×ih). */
    private boolean fits(int pl, int pw, int ph, int il, int iw, int ih) {
        return pl <= il && pw <= iw && ph <= ih;
    }

    private ValidationResponse buildResult(List<String> errors, List<String> warnings) {
        return ValidationResponse.builder()
                .canOptimize(errors.isEmpty())
                .errors(errors)
                .warnings(warnings)
                .build();
    }
}
