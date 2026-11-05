package fu.se184491.loadmaster_be.service.driver.Impl;

import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
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
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.DuplicateUnloadException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.IncompleteStopException;
import fu.se184491.loadmaster_be.exception.WrongStopException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.driver.UnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.service.driver.DriverService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import fu.se184491.loadmaster_be.service.trip.TripStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Việc của tài xế trên chuyến được giao cho chính mình.
 *
 * <p>Kiện của một điểm giao đọc qua yêu cầu giao của điểm đó ({@link TripPackageResolver}); đường
 * cũ qua đơn vận chuyển vẫn được đọc thêm cho dữ liệu cũ.</p>
 */
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
    private final AuditLogRepository auditLogRepository;
    private final DeliveryRequirementRepository deliveryRequirementRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final CurrentUserService currentUserService;
    private final TripStatusService tripStatusService;
    private final TripPackageResolver tripPackageResolver;
    private final TripLookupHelper tripLookupHelper;

    @Override
    @Transactional(readOnly = true)
    public List<DriverTripResponse> listTrips(Long driverId) {
        User driver = currentDriver(driverId);
        List<DriverTripResponse> trips = new ArrayList<>();
        for (Trip trip : tripRepository.findByDriver_IdOrderByDepartureTimeDesc(driver.getId())) {
            trips.add(toTripResponse(trip));
        }
        return trips;
    }

    @Override
    @Transactional
    public DriverTripResponse startTrip(Long tripId) {
        User driver = currentDriver(null);
        Trip trip = requireOwnTrip(String.valueOf(tripId));
        if (trip.getStatus() != TripStatus.LOADING) {
            throw new AppException(ErrorCode.LOADING_NOT_COMPLETED);
        }
        boolean loaded = loadingExecutionRepository
                .existsByLoadPlanJobTripIdAndStatus(trip.getId(), LoadingExecutionStatus.COMPLETED);
        if (!loaded) {
            throw new AppException(ErrorCode.LOADING_NOT_COMPLETED);
        }

        for (DeliveryRequirement requirement : deliveryRequirementRepository.findByTripId(trip.getId())) {
            if (requirement.getStatus() == DeliveryRequirementStatus.ASSIGNED) {
                requirement.setStatus(DeliveryRequirementStatus.IN_TRIP);
            }
        }
        Trip started = tripStatusService.transitionTo(trip.getId(), TripStatus.IN_TRANSIT, driver.getId());
        log.info("Driver {} started trip {}", driver.getId(), started.getTripCode());
        return toTripResponse(started);
    }

    @Override
    @Transactional(readOnly = true)
    public CurrentStopResponse getCurrentStop(Long tripId) {
        Trip trip = requireOwnTrip(String.valueOf(tripId));

        DeliveryStop currentStop = deliveryStopRepository
                .findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(trip.getId(), DeliveryStopStatus.COMPLETED)
                .orElseThrow(() -> new AppException(ErrorCode.NO_PENDING_DELIVERY_STOP));

        List<CargoPackage> stopPackages = tripPackageResolver.packagesOfStop(currentStop.getId());
        Set<Long> stopPackageIds = stopPackages.stream().map(CargoPackage::getId).collect(Collectors.toSet());
        List<StopPackageResponse> packageResponses = new ArrayList<>();

        Optional<LoadPlan> approvedPlanOpt = loadPlanRepository
                .findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(trip.getId());

        if (approvedPlanOpt.isPresent()) {
            List<PackagePlacement> stopPlacements = new ArrayList<>();
            for (PackagePlacement placement : packagePlacementRepository.findByLoadPlanId(approvedPlanOpt.get().getId())) {
                if (isPlacementForStop(placement, currentStop.getId(), stopPackageIds)) {
                    stopPlacements.add(placement);
                }
            }

            // Dỡ theo LIFO: kiện xếp sau cùng ra trước.
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

        // Chưa có phương án nào gắn với điểm: liệt kê kiện của điểm theo yêu cầu giao.
        if (packageResponses.isEmpty()) {
            int order = 1;
            for (CargoPackage pkg : stopPackages) {
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
                trip.getId(), currentStop.getId(), currentStop.getStopName(), packageResponses.size());

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
        User driver = currentDriver(driverId);
        CargoPackage pkg = cargoPackageRepository.findById(packageId)
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_NOT_FOUND));

        DeliveryStop packageStop = tripPackageResolver.stopOfPackage(pkg)
                .orElseThrow(() -> new AppException(ErrorCode.DELIVERY_STOP_NOT_FOUND));
        Trip trip = packageStop.getTrip();
        if (trip == null) {
            throw new AppException(ErrorCode.TRIP_NOT_FOUND);
        }
        requireDriverOf(trip, driver);
        if (trip.getStatus() != TripStatus.IN_TRANSIT) {
            throw new AppException(ErrorCode.GPS_TRIP_NOT_IN_TRANSIT);
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
                .companyId(AuditCompany.of(pkg.getCompany()))
                .actionType("DRIVER_UNLOAD")
                .entityName("UnloadConfirmation")
                .entityId(savedConfirmation.getId().toString())
                .user(driver)
                .newValues(Map.of(
                        "packageId", pkg.getId(),
                        "stopId", packageStop.getId(),
                        "trackingBarcode", pkg.getTrackingBarcode() != null ? pkg.getTrackingBarcode() : ""
                ))
                .createdAt(Instant.now())
                .build();
        auditLogRepository.save(auditLog);

        int packagesAtStop = tripPackageResolver.packagesOfStop(packageStop.getId()).size();
        long unloadedCount = unloadConfirmationRepository.countByDeliveryStopId(packageStop.getId());
        int remainingPackagesCount = Math.max(0, packagesAtStop - (int) unloadedCount);

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
        User driver = currentDriver(driverId);
        DeliveryStop stop = deliveryStopRepository.findById(stopId)
                .orElseThrow(() -> new AppException(ErrorCode.DELIVERY_STOP_NOT_FOUND));

        Trip trip = stop.getTrip();
        if (trip == null) {
            throw new AppException(ErrorCode.TRIP_NOT_FOUND);
        }
        requireDriverOf(trip, driver);
        if (trip.getStatus() != TripStatus.IN_TRANSIT) {
            throw new AppException(ErrorCode.GPS_TRIP_NOT_IN_TRANSIT);
        }
        if (stop.getStatus() == DeliveryStopStatus.COMPLETED) {
            throw new AppException(ErrorCode.NO_PENDING_DELIVERY_STOP);
        }

        int unconfirmedCount = 0;
        for (CargoPackage pkg : tripPackageResolver.packagesOfStop(stopId)) {
            if (!unloadConfirmationRepository.existsByCargoPackageId(pkg.getId())) {
                unconfirmedCount++;
            }
        }

        if (unconfirmedCount > 0) {
            throw new IncompleteStopException("Điểm dừng chưa hoàn tất: còn " + unconfirmedCount + " kiện hàng chưa được dỡ");
        }

        if (stop.getActualArrival() == null) {
            stop.setActualArrival(LocalDateTime.now());
        }
        stop.setStatus(DeliveryStopStatus.COMPLETED);
        deliveryStopRepository.save(stop);

        AuditLog stopAuditLog = AuditLog.builder()
                .companyId(AuditCompany.ofTrip(trip))
                .actionType("STOP_COMPLETED")
                .entityName("DeliveryStop")
                .entityId(stop.getId().toString())
                .user(driver)
                .newValues(Map.of(
                        "stopId", stop.getId(),
                        "tripId", trip.getId()
                ))
                .createdAt(Instant.now())
                .build();
        auditLogRepository.save(stopAuditLog);

        Optional<DeliveryStop> nextStopOpt = deliveryStopRepository
                .findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(trip.getId(), DeliveryStopStatus.COMPLETED);

        Long nextStopId = null;
        boolean tripCompleted = false;
        TripStatus tripStatus = trip.getStatus();

        if (nextStopOpt.isPresent()) {
            DeliveryStop nextStop = nextStopOpt.get();
            nextStop.setStatus(DeliveryStopStatus.ARRIVED);
            deliveryStopRepository.save(nextStop);
            nextStopId = nextStop.getId();
            log.info("Activated next stop {} ({}) for trip {}", nextStop.getId(), nextStop.getStopName(), trip.getId());
        } else {
            tripStatus = tripStatusService.transitionTo(trip.getId(), TripStatus.DELIVERED, driver.getId()).getStatus();
            tripCompleted = true;

            AuditLog tripAuditLog = AuditLog.builder()
                    .companyId(AuditCompany.ofTrip(trip))
                    .actionType("TRIP_COMPLETED")
                    .entityName("Trip")
                    .entityId(trip.getId().toString())
                    .user(driver)
                    .newValues(Map.of(
                            "tripId", trip.getId(),
                            "status", TripStatus.DELIVERED.name()
                    ))
                    .createdAt(Instant.now())
                    .build();
            auditLogRepository.save(tripAuditLog);

            log.info("All stops completed! Trip {} marked as DELIVERED", trip.getId());
        }

        return CompleteStopResponse.builder()
                .stopId(stop.getId())
                .stopStatus(stop.getStatus().name())
                .nextStopId(nextStopId)
                .tripStatus(tripStatus != null ? tripStatus.name() : null)
                .tripCompleted(tripCompleted)
                .message(tripCompleted
                        ? "Điểm giao cuối cùng đã hoàn tất, chuyến đi đã giao thành công (DELIVERED)"
                        : "Điểm giao hàng đã hoàn thành thành công, đã kích hoạt điểm dừng tiếp theo")
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Trip requireOwnTrip(String tripIdOrCode) {
        User driver = currentDriver(null);
        if (driver.getCompany() == null) {
            throw new AppException(ErrorCode.USER_COMPANY_NOT_FOUND);
        }
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, driver.getCompany().getId());
        requireDriverOf(trip, driver);
        return trip;
    }

    /** Người đăng nhập; {@code driverId} gửi kèm (client cũ) phải đúng là người đó. */
    private User currentDriver(Long driverId) {
        User current = currentUserService.getCurrentUser();
        if (driverId != null && !driverId.equals(current.getId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return current;
    }

    private static void requireDriverOf(Trip trip, User driver) {
        if (trip.getDriver() == null || !driver.getId().equals(trip.getDriver().getId())) {
            throw new AppException(ErrorCode.TRIP_NOT_ASSIGNED_TO_DRIVER);
        }
    }

    private DriverTripResponse toTripResponse(Trip trip) {
        return DriverTripResponse.builder()
                .tripId(trip.getId())
                .tripCode(trip.getTripCode())
                .vehicleLicensePlate(trip.getVehicle() != null ? trip.getVehicle().getLicensePlate() : null)
                .stopCount((int) deliveryStopRepository.countByTripId(trip.getId()))
                .departureTime(trip.getDepartureTime())
                .status(trip.getStatus() != null ? trip.getStatus().name() : null)
                .build();
    }

    private static boolean isPlacementForStop(PackagePlacement placement, Long stopId, Set<Long> stopPackageIds) {
        if (placement == null) {
            return false;
        }
        // Điểm giao thật của kiện (qua yêu cầu giao) là nguồn chuẩn: dỡ và hoàn tất điểm đều xét theo nó.
        // Vùng của phương án (stop_zone) chỉ dùng khi placement không gắn kiện.
        CargoPackage pkg = placement.getCargoPackage();
        if (pkg != null) {
            return stopPackageIds.contains(pkg.getId());
        }
        return placement.getStopZone() != null && stopId.equals(placement.getStopZone().getId());
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
