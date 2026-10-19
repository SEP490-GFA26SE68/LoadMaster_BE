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




}
