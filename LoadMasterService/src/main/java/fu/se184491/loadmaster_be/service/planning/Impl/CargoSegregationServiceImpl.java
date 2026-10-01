package fu.se184491.loadmaster_be.service.planning.Impl;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.dto.response.planning.SegregationResult;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.planning.CargoSegregationService;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CargoSegregationServiceImpl implements CargoSegregationService {

    private final TripRepository tripRepository;
    private final CargoPackageRepository cargoPackageRepository;

    @Override
    public SegregationResult checkTrip(Long companyId, Long tripId) {
        Trip trip = tripRepository.findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));

        List<CargoPackage> packages = cargoPackageRepository
                .findByOrderDeliveryStopTripIdOrderByIdAsc(tripId);
        Map<HandlingClass, List<Long>> packageIdsByClass = new LinkedHashMap<>();
        for (CargoPackage cargoPackage : packages) {
            packageIdsByClass.computeIfAbsent(
                            cargoPackage.getHandlingClass(), ignored -> new ArrayList<>())
                    .add(cargoPackage.getId());
        }

        List<SegregationResult.Group> groups = packageIdsByClass.entrySet().stream()
                .map(entry -> SegregationResult.Group.builder()
                        .handlingClass(entry.getKey())
                        .packageCount(entry.getValue().size())
                        .packageIds(List.copyOf(entry.getValue()))
                        .build())
                .toList();

        List<SegregationResult.Conflict> conflicts = new ArrayList<>();
        if (packageIdsByClass.containsKey(HandlingClass.STANDARD)
                && packageIdsByClass.containsKey(HandlingClass.FRAGILE)) {
            List<Long> affectedPackageIds = packages.stream()
                    .filter(cargoPackage -> cargoPackage.getHandlingClass() == HandlingClass.STANDARD
                            || cargoPackage.getHandlingClass() == HandlingClass.FRAGILE)
                    .map(CargoPackage::getId)
                    .toList();
            conflicts.add(SegregationResult.Conflict.builder()
                    .ruleCode("FRAGILE_STANDARD_MIX")
                    .message("FRAGILE packages cannot share a trip with STANDARD packages")
                    .affectedPackageIds(affectedPackageIds)
                    .build());
        }
        boolean hazardousCapable = trip.getVehicle() != null
                && trip.getVehicle().getVehicleType() != null
                && Boolean.TRUE.equals(trip.getVehicle().getVehicleType().getHazardousCapable());
        if (packageIdsByClass.containsKey(HandlingClass.HAZARDOUS) && !hazardousCapable) {
            conflicts.add(SegregationResult.Conflict.builder()
                    .ruleCode("HAZARDOUS_VEHICLE_REQUIRED")
                    .message("HAZARDOUS packages require a hazardous-capable vehicle")
                    .affectedPackageIds(List.copyOf(packageIdsByClass.get(HandlingClass.HAZARDOUS)))
                    .build());
        }

        return SegregationResult.builder()
                .groups(groups)
                .conflicts(List.copyOf(conflicts))
                .handlingClassLock(trip.getHandlingClassLock())
                .overrideReason(trip.getOverrideReason())
                .build();
    }

    @Override
    @Transactional
    public void validateAddition(
            Long companyId,
            Long tripId,
            List<Long> packageIds,
            boolean override,
            String overrideReason
    ) {
        Trip trip = tripRepository.findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));

        List<Long> distinctPackageIds = packageIds.stream().distinct().toList();
        List<CargoPackage> candidates = cargoPackageRepository
                .findAllByIdInAndOrderCompanyId(distinctPackageIds, companyId);
        if (candidates.size() != distinctPackageIds.size()) {
            throw new AppException(ErrorCode.PACKAGE_NOT_FOUND);
        }

        List<CargoPackage> currentPackages = cargoPackageRepository
                .findByOrderDeliveryStopTripIdOrderByIdAsc(tripId);
        List<CargoPackage> combinedPackages = new ArrayList<>(currentPackages);
        combinedPackages.addAll(candidates);
        boolean hasStandard = combinedPackages.stream()
                .anyMatch(cargoPackage -> cargoPackage.getHandlingClass() == HandlingClass.STANDARD);
        boolean hasFragile = combinedPackages.stream()
                .anyMatch(cargoPackage -> cargoPackage.getHandlingClass() == HandlingClass.FRAGILE);
        boolean hasHazardous = combinedPackages.stream()
                .anyMatch(cargoPackage -> cargoPackage.getHandlingClass() == HandlingClass.HAZARDOUS);
        boolean hazardousCapable = trip.getVehicle() != null
                && trip.getVehicle().getVehicleType() != null
                && Boolean.TRUE.equals(trip.getVehicle().getVehicleType().getHazardousCapable());
        boolean hasConflict = (hasStandard && hasFragile)
                || (hasHazardous && !hazardousCapable);
        if (hasConflict) {
            if (!override) {
                throw new AppException(ErrorCode.CARGO_SEGREGATION_CONFLICT);
            }
            if (overrideReason == null || overrideReason.isBlank()) {
                throw new AppException(ErrorCode.INVALID_INPUT);
            }
            trip.setOverrideReason(overrideReason.trim());
        }
        if (currentPackages.isEmpty()
                && !candidates.isEmpty()
                && trip.getHandlingClassLock() == null) {
            trip.setHandlingClassLock(candidates.getFirst().getHandlingClass().name());
        }
    }
}
