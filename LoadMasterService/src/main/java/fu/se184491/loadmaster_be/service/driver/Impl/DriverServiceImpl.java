package fu.se184491.loadmaster_be.service.driver.Impl;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.driver.CompleteStopResponse;
import fu.se184491.loadmaster_be.dto.response.driver.ConfirmUnloadResponse;
import fu.se184491.loadmaster_be.dto.response.driver.CurrentStopResponse;
import fu.se184491.loadmaster_be.dto.response.driver.DriverTripResponse;
import fu.se184491.loadmaster_be.dto.response.driver.StopPackageResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.driver.UnloadConfirmation;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.DuplicateUnloadException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.IncompleteStopException;
import fu.se184491.loadmaster_be.exception.WrongStopException;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.driver.UnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.driver.DriverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DriverServiceImpl implements DriverService {

    private final TripRepository tripRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final LoadPlanRepository loadPlanRepository;
    private final PackagePlacementRepository packagePlacementRepository;
    private final CargoPackageRepository cargoPackageRepository;
    private final UnloadConfirmationRepository unloadConfirmationRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    @Override
    @Transactional(readOnly = true)
    public List<DriverTripResponse> getDriverTrips(Long driverId) {
        List<Trip> trips = (driverId != null)
                ? tripRepository.findByVehicleDriverIdAndStatus(driverId, TripStatus.READY_FOR_DELIVERY)
                : tripRepository.findByStatus(TripStatus.READY_FOR_DELIVERY);

        List<DriverTripResponse> responses = new ArrayList<>();
        for (Trip trip : trips) {
            int stopCount = (int) deliveryStopRepository.countByTripId(trip.getId());
            String licensePlate = (trip.getVehicle() != null) ? trip.getVehicle().getLicensePlate() : null;

            responses.add(DriverTripResponse.builder()
                    .tripId(trip.getId())
                    .tripCode(trip.getTripCode())
                    .vehicleLicensePlate(licensePlate)
                    .stopCount(stopCount)
                    .departureTime(trip.getDepartureTime())
                    .status(trip.getStatus() != null ? trip.getStatus().name() : null)
                    .build());
        }

        log.info("Found {} trips ready for delivery for driverId {}", responses.size(), driverId);
        return responses;
    }

    @Override
    @Transactional(readOnly = true)
    public CurrentStopResponse getCurrentStop(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));

        DeliveryStop currentStop = deliveryStopRepository
                .findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(tripId, DeliveryStopStatus.COMPLETED)
                .orElseThrow(() -> new AppException(ErrorCode.NO_PENDING_DELIVERY_STOP));

        List<StopPackageResponse> packageResponses = new ArrayList<>();

        Optional<LoadPlan> approvedPlanOpt = loadPlanRepository
                .findFirstByJobTripIdAndApprovedTrueOrderByVersionDesc(tripId);

        if (approvedPlanOpt.isPresent()) {
            List<PackagePlacement> placements = packagePlacementRepository.findByLoadPlanId(approvedPlanOpt.get().getId());

            List<PackagePlacement> stopPlacements = new ArrayList<>();
            for (PackagePlacement placement : placements) {
                if (isPlacementForStop(placement, currentStop.getId())) {
                    stopPlacements.add(placement);
                }
            }

            // LIFO unloading order: reverse stepSequence (highest stepSequence unloaded first)
            stopPlacements.sort(Comparator.comparing(
                    PackagePlacement::getStepSequence,
                    Comparator.nullsLast(Comparator.reverseOrder())
            ));

            int order = 1;
            for (PackagePlacement placement : stopPlacements) {
                CargoPackage pkg = placement.getCargoPackage();
                packageResponses.add(StopPackageResponse.builder()
                        .packageId(pkg != null ? pkg.getId() : null)
                        .packageName(getPackageDisplayName(pkg))
                        .trackingBarcode(pkg != null ? pkg.getTrackingBarcode() : null)
                        .weight(pkg != null ? pkg.getActualWeightKg() : null)
                        .stepSequence(placement.getStepSequence())
                        .unloadOrder(order++)
                        .build());
            }
        }

        // Fallback if no placements were linked to the stop
        if (packageResponses.isEmpty()) {
            List<CargoPackage> directPackages = cargoPackageRepository.findByOrderDeliveryStopId(currentStop.getId());
            int order = 1;
            for (CargoPackage pkg : directPackages) {
                packageResponses.add(StopPackageResponse.builder()
                        .packageId(pkg.getId())
                        .packageName(getPackageDisplayName(pkg))
                        .trackingBarcode(pkg.getTrackingBarcode())
                        .weight(pkg.getActualWeightKg())
                        .stepSequence(null)
                        .unloadOrder(order++)
                        .build());
            }
        }

        log.info("Current stop for tripId {}: stopId {} ({}), with {} packages to unload",
                tripId, currentStop.getId(), currentStop.getStopName(), packageResponses.size());

        return CurrentStopResponse.builder()
                .stopId(currentStop.getId())
                .tripId(trip.getId())
                .stopSequence(currentStop.getStopSequence())
                .stopName(currentStop.getStopName())
                .address(currentStop.getAddress())
                .status(currentStop.getStatus() != null ? currentStop.getStatus().name() : null)
                .packages(packageResponses)
                .build();
    }

    @Override
    @Transactional
    public ConfirmUnloadResponse confirmUnload(Long packageId, Long driverId) {
        CargoPackage pkg = cargoPackageRepository.findById(packageId)
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_NOT_FOUND));

        if (pkg.getOrder() == null || pkg.getOrder().getDeliveryStop() == null) {
            throw new AppException(ErrorCode.DELIVERY_STOP_NOT_FOUND);
        }
        DeliveryStop packageStop = pkg.getOrder().getDeliveryStop();
        Trip trip = packageStop.getTrip();
        if (trip == null) {
            throw new AppException(ErrorCode.TRIP_NOT_FOUND);
        }

        DeliveryStop currentStop = deliveryStopRepository
                .findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(trip.getId(), DeliveryStopStatus.COMPLETED)
                .orElseThrow(() -> new AppException(ErrorCode.NO_PENDING_DELIVERY_STOP));

        if (unloadConfirmationRepository.existsByCargoPackageId(packageId) || pkg.getStatus() == PackageStatus.DELIVERED) {
            throw new DuplicateUnloadException("Kiện hàng này đã được dỡ trước đó: " + pkg.getTrackingBarcode());
        }

        if (!packageStop.getId().equals(currentStop.getId())) {
            if (packageStop.getStopSequence() > currentStop.getStopSequence()) {
                throw new WrongStopException("Kiện hàng thuộc điểm dừng sau (Stop " + packageStop.getStopSequence()
                        + "), chưa thể dỡ tại điểm này (Stop " + currentStop.getStopSequence() + ")");
            } else {
                throw new WrongStopException("Kiện hàng không thuộc điểm dừng hiện tại");
            }
        }

        User driver = (driverId != null) ? userRepository.findById(driverId).orElse(null) : null;

        UnloadConfirmation confirmation = UnloadConfirmation.builder()
                .cargoPackage(pkg)
                .deliveryStop(packageStop)
                .driver(driver)
                .unloadedAt(LocalDateTime.now())
                .build();
        UnloadConfirmation savedConfirmation = unloadConfirmationRepository.save(confirmation);

        pkg.setStatus(PackageStatus.DELIVERED);
        cargoPackageRepository.save(pkg);

        AuditLog auditLog = AuditLog.builder()
                .actionType("DRIVER_UNLOAD")
                .entityName("UnloadConfirmation")
                .entityId(savedConfirmation.getId().toString())
                .user(driver)
                .newValues(Map.of(
                        "packageId", pkg.getId(),
                        "stopId", packageStop.getId(),
                        "trackingBarcode", pkg.getTrackingBarcode() != null ? pkg.getTrackingBarcode() : ""
                ))
                .createdAt(LocalDateTime.now())
                .build();
        auditLogRepository.save(auditLog);

        List<CargoPackage> packagesAtStop = cargoPackageRepository.findByOrderDeliveryStopId(packageStop.getId());
        long unloadedCount = unloadConfirmationRepository.countByDeliveryStopId(packageStop.getId());
        int remainingPackagesCount = Math.max(0, packagesAtStop.size() - (int) unloadedCount);

        log.info("Driver confirmed unload for package {} at stop {}. Remaining: {}",
                pkg.getId(), packageStop.getId(), remainingPackagesCount);

        return ConfirmUnloadResponse.builder()
                .packageId(pkg.getId())
                .trackingBarcode(pkg.getTrackingBarcode())
                .stopId(packageStop.getId())
                .remainingPackagesCount(remainingPackagesCount)
                .message("Đã xác nhận dỡ kiện hàng " + pkg.getTrackingBarcode() + " thành công")
                .build();
    }

    @Override
    @Transactional
    public CompleteStopResponse completeStop(Long stopId, Long driverId) {
        DeliveryStop stop = deliveryStopRepository.findById(stopId)
                .orElseThrow(() -> new AppException(ErrorCode.DELIVERY_STOP_NOT_FOUND));

        Trip trip = stop.getTrip();
        if (trip == null) {
            throw new AppException(ErrorCode.TRIP_NOT_FOUND);
        }

        List<CargoPackage> packagesAtStop = cargoPackageRepository.findByOrderDeliveryStopId(stopId);
        int unconfirmedCount = 0;
        for (CargoPackage pkg : packagesAtStop) {
            if (!unloadConfirmationRepository.existsByCargoPackageId(pkg.getId())) {
                unconfirmedCount++;
            }
        }

        if (unconfirmedCount > 0) {
            throw new IncompleteStopException("Điểm dừng chưa hoàn tất: còn " + unconfirmedCount + " kiện hàng chưa được dỡ");
        }

        stop.setStatus(DeliveryStopStatus.COMPLETED);
        deliveryStopRepository.save(stop);

        User driver = (driverId != null) ? userRepository.findById(driverId).orElse(null) : null;

        AuditLog stopAuditLog = AuditLog.builder()
                .actionType("STOP_COMPLETED")
                .entityName("DeliveryStop")
                .entityId(stop.getId().toString())
                .user(driver)
                .newValues(Map.of(
                        "stopId", stop.getId(),
                        "tripId", trip.getId()
                ))
                .createdAt(LocalDateTime.now())
                .build();
        auditLogRepository.save(stopAuditLog);

        Optional<DeliveryStop> nextStopOpt = deliveryStopRepository
                .findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(trip.getId(), DeliveryStopStatus.COMPLETED);

        Long nextStopId = null;
        boolean tripCompleted = false;

        if (nextStopOpt.isPresent()) {
            DeliveryStop nextStop = nextStopOpt.get();
            nextStop.setStatus(DeliveryStopStatus.ARRIVED);
            deliveryStopRepository.save(nextStop);
            nextStopId = nextStop.getId();
            log.info("Activated next stop {} ({}) for trip {}", nextStop.getId(), nextStop.getStopName(), trip.getId());
        } else {
            trip.setStatus(TripStatus.DELIVERED);
            tripRepository.save(trip);
            tripCompleted = true;

            AuditLog tripAuditLog = AuditLog.builder()
                    .actionType("TRIP_COMPLETED")
                    .entityName("Trip")
                    .entityId(trip.getId().toString())
                    .user(driver)
                    .newValues(Map.of(
                            "tripId", trip.getId(),
                            "status", TripStatus.DELIVERED.name()
                    ))
                    .createdAt(LocalDateTime.now())
                    .build();
            auditLogRepository.save(tripAuditLog);

            log.info("All stops completed! Trip {} marked as DELIVERED", trip.getId());
        }

        return CompleteStopResponse.builder()
                .stopId(stop.getId())
                .stopStatus(stop.getStatus().name())
                .nextStopId(nextStopId)
                .tripStatus(trip.getStatus() != null ? trip.getStatus().name() : null)
                .tripCompleted(tripCompleted)
                .message(tripCompleted
                        ? "Điểm giao cuối cùng đã hoàn tất, chuyến đi đã giao thành công (DELIVERED)"
                        : "Điểm giao hàng đã hoàn thành thành công, đã kích hoạt điểm dừng tiếp theo")
                .build();
    }

    private boolean isPlacementForStop(PackagePlacement placement, Long stopId) {
        if (placement == null || placement.getCargoPackage() == null) {
            return false;
        }
        CargoPackage pkg = placement.getCargoPackage();
        if (pkg.getOrder() == null || pkg.getOrder().getDeliveryStop() == null) {
            return false;
        }
        return stopId.equals(pkg.getOrder().getDeliveryStop().getId());
    }

    private String getPackageDisplayName(CargoPackage cargoPackage) {
        if (cargoPackage == null) {
            return "Kiện hàng";
        }
        if (cargoPackage.getPackageType() != null && cargoPackage.getPackageType().getName() != null) {
            return cargoPackage.getPackageType().getName();
        }
        return cargoPackage.getTrackingBarcode() != null
                ? cargoPackage.getTrackingBarcode()
                : "Kiện hàng " + cargoPackage.getId();
    }
}
