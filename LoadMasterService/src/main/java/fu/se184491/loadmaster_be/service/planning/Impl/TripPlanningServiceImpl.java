package fu.se184491.loadmaster_be.service.planning.Impl;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.order.OrderStatus;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.planning.AddPackagesToTripRequest;
import fu.se184491.loadmaster_be.dto.request.planning.CreateTripRequest;
import fu.se184491.loadmaster_be.dto.response.planning.TripDetailResponse;
import fu.se184491.loadmaster_be.dto.route.RouteOptimizationResult;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.service.planning.TripPlanningService;
import fu.se184491.loadmaster_be.service.planning.CargoSegregationService;
import fu.se184491.loadmaster_be.service.route.RouteOptimizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class TripPlanningServiceImpl implements TripPlanningService {

    private static final DateTimeFormatter TRIP_CODE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final TripRepository tripRepository;
    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final CargoPackageRepository cargoPackageRepository;
    private final DeliveryRequirementRepository deliveryRequirementRepository;
    private final CargoSegregationService cargoSegregationService;
    private final RouteOptimizationService routeOptimizationService;

    @Override
    public TripDetailResponse createTrip(Long companyId, CreateTripRequest request) {
        Vehicle vehicle = vehicleRepository.findById(request.vehicleId())
                .filter(candidate -> candidate.getCompany() != null
                        && companyId.equals(candidate.getCompany().getId()))
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_NOT_FOUND));
        User driver = userRepository.findById(request.driverId())
                .filter(candidate -> candidate.getCompany() != null
                        && companyId.equals(candidate.getCompany().getId())
                        && candidate.getUserRoleType() == UserRole.DRIVER)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        vehicle.setDriver(driver);
        Trip trip = tripRepository.save(Trip.builder()
                .company(vehicle.getCompany())
                .vehicle(vehicle)
                .tripCode(generateTripCode())
                .departureTime(request.departureTime())
                .status(TripStatus.DRAFT)
                .build());
        return toResponse(trip);
    }

    @Override
    public TripDetailResponse addPackages(
            Long companyId,
            Long tripId,
            AddPackagesToTripRequest request
    ) {
        Trip trip = findTrip(companyId, tripId);
        List<Long> packageIds = request.packageIds().stream().distinct().toList();
        cargoSegregationService.validateAddition(
                companyId, tripId, packageIds, request.override(), request.overrideReason());
        List<CargoPackage> packages = cargoPackageRepository
                .findAllByIdInAndOrderCompanyId(packageIds, companyId);
        if (packages.size() != packageIds.size()) {
            throw new AppException(ErrorCode.PACKAGE_NOT_FOUND);
        }

        List<DeliveryRequirement> requirements = deliveryRequirementRepository
                .findByCompanyAndPackageIds(companyId, packageIds);
        Map<Long, DeliveryRequirement> requirementByPackage = new LinkedHashMap<>();
        for (DeliveryRequirement requirement : requirements) {
            for (CargoPackage cargoPackage : requirement.getPackages()) {
                requirementByPackage.put(cargoPackage.getId(), requirement);
            }
        }
        if (!requirementByPackage.keySet().containsAll(packageIds)) {
            throw new AppException(ErrorCode.DELIVERY_REQUIREMENT_NOT_FOUND);
        }

        List<DeliveryStop> existingStops = deliveryStopRepository
                .findByTripIdOrderByStopSequenceAsc(tripId);
        Map<Long, DeliveryStop> stopByRequirement = new LinkedHashMap<>();
        for (CargoPackage cargoPackage : packages) {
            DeliveryRequirement requirement = requirementByPackage.get(cargoPackage.getId());
            DeliveryStop stop = stopByRequirement.computeIfAbsent(requirement.getId(), ignored -> {
                DeliveryStop created = DeliveryStop.builder()
                        .trip(trip)
                        .stopSequence(existingStops.size() + stopByRequirement.size() + 1)
                        .stopName(requirement.getDestination())
                        .address(requirement.getDestination())
                        .latitude(requirement.getDestinationLat())
                        .longitude(requirement.getDestinationLng())
                        .status(DeliveryStopStatus.PENDING)
                        .build();
                return deliveryStopRepository.save(created);
            });
            if (cargoPackage.getOrder() == null) {
                throw new AppException(ErrorCode.INVALID_INPUT);
            }
            cargoPackage.getOrder().setDeliveryStop(stop);
            cargoPackage.getOrder().setStatus(OrderStatus.ASSIGNED);
            cargoPackage.setStatus(PackageStatus.PLANNED);
            requirement.setStatus(DeliveryRequirementStatus.ASSIGNED);
        }
        cargoPackageRepository.saveAll(packages);
        return toResponse(trip);
    }

    @Override
    public void removePackage(Long companyId, Long tripId, Long packageId) {
        Trip trip = findTrip(companyId, tripId);
        CargoPackage cargoPackage = cargoPackageRepository
                .findAllByIdInAndOrderCompanyId(List.of(packageId), companyId).stream()
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_NOT_FOUND));
        if (cargoPackage.getOrder() == null
                || cargoPackage.getOrder().getDeliveryStop() == null
                || cargoPackage.getOrder().getDeliveryStop().getTrip() == null
                || !tripId.equals(cargoPackage.getOrder().getDeliveryStop().getTrip().getId())) {
            throw new AppException(ErrorCode.PACKAGE_NOT_FOUND);
        }

        DeliveryStop stop = cargoPackage.getOrder().getDeliveryStop();
        boolean stopBecomesEmpty = cargoPackageRepository.findByOrderDeliveryStopId(stop.getId()).size() == 1;
        boolean tripBecomesEmpty = cargoPackageRepository
                .findByOrderDeliveryStopTripIdOrderByIdAsc(tripId).size() == 1;
        cargoPackage.getOrder().setDeliveryStop(null);
        cargoPackage.getOrder().setStatus(OrderStatus.PENDING);
        cargoPackage.setStatus(PackageStatus.PENDING);
        cargoPackageRepository.save(cargoPackage);
        if (stopBecomesEmpty) {
            deliveryStopRepository.delete(stop);
        }
        if (tripBecomesEmpty) {
            trip.setHandlingClassLock(null);
            trip.setOverrideReason(null);
        }
    }

    @Override
    public RouteOptimizationResult optimizeRoute(Long companyId, Long tripId) {
        findTrip(companyId, tripId);
        return routeOptimizationService.optimizeRoute(tripId);
    }

    @Override
    public RouteOptimizationResult refreshEta(Long companyId, Long tripId) {
        return optimizeRoute(companyId, tripId);
    }

    private String generateTripCode() {
        return "TRIP-" + LocalDate.now().format(TRIP_CODE_DATE) + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private TripDetailResponse toResponse(Trip trip) {
        List<DeliveryStop> stops = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId());
        List<CargoPackage> packages = cargoPackageRepository
                .findByOrderDeliveryStopTripIdOrderByIdAsc(trip.getId());
        Map<Long, List<Long>> packageIdsByStop = packages.stream().collect(
                java.util.stream.Collectors.groupingBy(
                        cargoPackage -> cargoPackage.getOrder().getDeliveryStop().getId(),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(CargoPackage::getId, java.util.stream.Collectors.toList())));
        return new TripDetailResponse(
                trip.getId(),
                trip.getTripCode(),
                trip.getVehicle().getId(),
                trip.getVehicle().getDriver() == null ? null : trip.getVehicle().getDriver().getId(),
                trip.getDepartureTime(),
                trip.getStatus(),
                packages.stream().map(CargoPackage::getId).toList(),
                stops.stream().map(stop -> new TripDetailResponse.Stop(
                        stop.getId(),
                        stop.getStopSequence(),
                        stop.getStopName(),
                        stop.getAddress(),
                        stop.getLatitude() == null ? null : stop.getLatitude().doubleValue(),
                        stop.getLongitude() == null ? null : stop.getLongitude().doubleValue(),
                        stop.getPlannedArrival(),
                        packageIdsByStop.getOrDefault(stop.getId(), List.of()))).toList());
    }

    private Trip findTrip(Long companyId, Long tripId) {
        return tripRepository.findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
    }
}
