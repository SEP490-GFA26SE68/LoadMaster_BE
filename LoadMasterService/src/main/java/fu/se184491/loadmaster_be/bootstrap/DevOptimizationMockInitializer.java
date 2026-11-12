package fu.se184491.loadmaster_be.bootstrap;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.billing.AlgorithmTier;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.order.OrderStatus;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.entity.Customer;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.CreditAccount;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.company.CompanyWarehouse;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.helpers.KeycloakAccounts;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.CreditAccountRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.cargo.PackageTypeRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyWarehouseRepository;
import fu.se184491.loadmaster_be.repository.customer.CustomerRepository;
import fu.se184491.loadmaster_be.repository.order.OrderRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dữ liệu dev để chạy tối ưu xếp hàng 3D từ FE: điều phối viên Keycloak, xe, kiện, chuyến
 * {@code MOCK-OPT-001} đủ cho OptimizeService đọc Trip → DeliveryRequirement → Package → DeliveryStop.
 */
@Slf4j
@Component
@Profile("dev")
@Order(3)
@RequiredArgsConstructor
public class DevOptimizationMockInitializer implements ApplicationRunner {

    static final String COMPANY_CODE = "LM-LOGISTICS";
    static final String TRIP_CODE = "MOCK-OPT-001";
    static final String VEHICLE_PLATE = "51C-MOCK.01";
    static final String PACKAGE_TYPE_CODE = "MOCK-CARTON-60";
    static final String PLAN_CODE = "ULTIMATE_MONTHLY";
    static final String VEHICLE_TYPE_NAME = "Isuzu NQR 550 mock";

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final CompanyWarehouseRepository warehouseRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final VehicleRepository vehicleRepository;
    private final PackageTypeRepository packageTypeRepository;
    private final CargoPackageRepository packageRepository;
    private final CustomerRepository customerRepository;
    private final DeliveryRequirementRepository deliveryRequirementRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final OrderRepository orderRepository;
    private final TripRepository tripRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.bootstrap.dispatcher.email}")
    private String dispatcherEmail;

    @Value("${app.bootstrap.dispatcher.password}")
    private String dispatcherPassword;

    @Value("${app.bootstrap.dispatcher.full-name}")
    private String dispatcherFullName;

    @Override
    public void run(ApplicationArguments args) {
        Company company = companyRepository.findByCompanyCode(COMPANY_CODE).orElse(null);
        if (company == null) {
            log.warn("Bo seed toi uu: chua co cong ty {}", COMPANY_CODE);
            return;
        }

        User dispatcher = ensureDispatcher(company);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> seedTrip(company, dispatcher));
    }

    private void seedTrip(Company company, User dispatcher) {
        ensureCredits(company);
        ensureSubscription(company);

        if (tripRepository.findByTripCode(TRIP_CODE).isPresent()) {
            log.info("Chuyen mock {} da co, khong seed lai", TRIP_CODE);
            return;
        }

        CompanyWarehouse warehouse = ensureWarehouse(company);
        VehicleType vehicleType = ensureVehicleType(company);
        Vehicle vehicle = ensureVehicle(company, vehicleType);
        PackageType packageType = ensurePackageType(company);
        Trip trip = createTrip(company, warehouse, vehicle, dispatcher);
        seedStopsAndPackages(company, trip, packageType);
        log.info(
                "Da seed chuyen toi uu mock {} (id={}) cho dispatcher {}",
                TRIP_CODE,
                trip.getId(),
                dispatcherEmail
        );
    }

    private User ensureDispatcher(Company company) {
        User existing = userRepository.findByEmail(dispatcherEmail).orElse(null);
        if (existing != null) {
            if (existing.getCompany() == null) {
                existing.setCompany(company);
                return userRepository.save(existing);
            }
            return existing;
        }

        String keycloakUserId = keycloakAdminClient.findUserIdByEmail(dispatcherEmail);
        if (keycloakUserId != null) {
            return userRepository.save(dispatcherUser(keycloakUserId, company));
        }
        // Tạo trên Keycloak và lưu DB trong một transaction: lưu lỗi thì tài khoản Keycloak vừa tạo bị xoá lại
        return new TransactionTemplate(transactionManager).execute(status -> userRepository.save(dispatcherUser(
                KeycloakAccounts.provision(keycloakAdminClient, userRepository, dispatcherEmail, dispatcherFullName,
                        dispatcherPassword, false, UserRole.DISPATCHER, false),
                company)));
    }

    private User dispatcherUser(String keycloakUserId, Company company) {
        return User.builder()
                .keycloakId(keycloakUserId)
                .email(dispatcherEmail)
                .fullName(dispatcherFullName)
                .status(UserStatus.ACTIVE)
                .userRoleType(UserRole.DISPATCHER)
                .company(company)
                .build();
    }

    private void ensureCredits(Company company) {
        if (creditAccountRepository.existsByCompanyId(company.getId())) {
            return;
        }
        creditAccountRepository.save(CreditAccount.builder()
                .company(company)
                .balance(100)
                .build());
    }

    private void ensureSubscription(Company company) {
        if (companySubscriptionRepository.findByCompanyId(company.getId()).isPresent()) {
            return;
        }
        SubscriptionPlan plan = subscriptionPlanRepository.findByPlanCode(PLAN_CODE)
                .or(() -> subscriptionPlanRepository.findByTierAndActiveTrue(SubscriptionTier.ULTIMATE))
                .orElseGet(() -> subscriptionPlanRepository.save(SubscriptionPlan.builder()
                        .planCode("DEV-MOCK-ULTIMATE")
                        .name("Dev Ultimate")
                        .price(BigDecimal.ZERO)
                        .billingCycle(BillingCycle.MONTHLY)
                        .tier(SubscriptionTier.ULTIMATE)
                        .monthlyCredits(999)
                        .algorithmTier(AlgorithmTier.EP_DBLF_GA_AI)
                        .active(true)
                        .build()));
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        companySubscriptionRepository.save(CompanySubscription.builder()
                .company(company)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(now)
                .expiresAt(now.plusYears(1))
                .autoRenew(false)
                .build());
    }

    private CompanyWarehouse ensureWarehouse(Company company) {
        return warehouseRepository.findByCompanyIdAndIsDefaultTrue(company.getId())
                .orElseGet(() -> warehouseRepository.save(CompanyWarehouse.builder()
                        .company(company)
                        .warehouseName("Kho Long Binh mock")
                        .address("KCN Long Binh, Bien Hoa, Dong Nai")
                        .latitude(new BigDecimal("10.9571000"))
                        .longitude(new BigDecimal("106.8634000"))
                        .isDefault(true)
                        .isActive(true)
                        .build()));
    }

    private VehicleType ensureVehicleType(Company company) {
        return vehicleTypeRepository.findByCompanyId(company.getId(), Pageable.unpaged())
                .getContent()
                .stream()
                .filter(type -> VEHICLE_TYPE_NAME.equals(type.getName()))
                .findFirst()
                .orElseGet(() -> vehicleTypeRepository.save(VehicleType.builder()
                        .company(company)
                        .name(VEHICLE_TYPE_NAME)
                        .innerLength(570)
                        .innerWidth(210)
                        .innerHeight(215)
                        .maxPayloadKg(new BigDecimal("5500.00"))
                        .doorWidth(200)
                        .doorHeight(205)
                        .frontAxleLimitKg(new BigDecimal("3600.00"))
                        .rearAxleLimitKg(new BigDecimal("6600.00"))
                        .frontAxleX(new BigDecimal("-100.00"))
                        .rearAxleX(new BigDecimal("377.50"))
                        .emptyFrontAxleLoadKg(new BigDecimal("2300.00"))
                        .emptyRearAxleLoadKg(new BigDecimal("1700.00"))
                        .maxCogOffsetRatio(new BigDecimal("0.1500"))
                        .hazardousCapable(false)
                        .build()));
    }

    private Vehicle ensureVehicle(Company company, VehicleType vehicleType) {
        return vehicleRepository.findByLicensePlateIgnoreCase(VEHICLE_PLATE)
                .orElseGet(() -> vehicleRepository.save(Vehicle.builder()
                        .company(company)
                        .vehicleType(vehicleType)
                        .name("Isuzu NQR 550 mock")
                        .licensePlate(VEHICLE_PLATE)
                        .status(VehicleStatus.AVAILABLE)
                        .build()));
    }

    private PackageType ensurePackageType(Company company) {
        return packageTypeRepository.findByCompanyIdAndTypeCodeIgnoreCase(
                        company.getId(),
                        PACKAGE_TYPE_CODE
                )
                .orElseGet(() -> packageTypeRepository.save(PackageType.builder()
                        .company(company)
                        .typeCode(PACKAGE_TYPE_CODE)
                        .name("Thung carton 60x40x40")
                        .length(60)
                        .width(40)
                        .height(40)
                        .weightKg(new BigDecimal("18.00"))
                        .maxStackingWeightKg(new BigDecimal("80.00"))
                        .rotationAllowed(true)
                        .allowRotateX(false)
                        .allowRotateY(false)
                        .allowRotateZ(true)
                        .isFragile(false)
                        .build()));
    }

    private Trip createTrip(
            Company company,
            CompanyWarehouse warehouse,
            Vehicle vehicle,
            User dispatcher
    ) {
        LocalDateTime departure = LocalDateTime.now()
                .plusDays(1)
                .withHour(8)
                .withMinute(0)
                .withSecond(0)
                .truncatedTo(ChronoUnit.SECONDS);
        return tripRepository.save(Trip.builder()
                .company(company)
                .originWarehouse(warehouse)
                .vehicle(vehicle)
                .createdByDispatcher(dispatcher)
                .tripName("Mock toi uu xep hang Thu Duc – An Phu")
                .tripCode(TRIP_CODE)
                .originName(warehouse.getWarehouseName())
                .originAddress(warehouse.getAddress())
                .originLatitude(warehouse.getLatitude())
                .originLongitude(warehouse.getLongitude())
                .departureTime(departure)
                .status(TripStatus.PLANNED)
                .handlingClassLock(HandlingClass.STANDARD.name())
                .createdAt(Instant.now())
                .build());
    }

    private void seedStopsAndPackages(Company company, Trip trip, PackageType packageType) {
        LocalDateTime deadline = LocalDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        StopSeed[] stops = {
                new StopSeed(
                        1,
                        "BHX Thu Duc mock",
                        "Bach Hoa Xanh Thu Duc, TP. Ho Chi Minh",
                        new BigDecimal("10.8502000"),
                        new BigDecimal("106.7719000"),
                        "0901000001"
                ),
                new StopSeed(
                        2,
                        "Mega An Phu mock",
                        "Mega Market An Phu, TP. Ho Chi Minh",
                        new BigDecimal("10.8024000"),
                        new BigDecimal("106.7471000"),
                        "0901000002"
                ),
        };

        int packageIndex = 1;
        for (StopSeed seed : stops) {
            Customer customer = customerRepository.save(Customer.builder()
                    .company(company)
                    .name(seed.stopName())
                    .contactPhone(seed.phone())
                    .address(seed.address())
                    .build());

            DeliveryRequirement requirement = deliveryRequirementRepository.save(DeliveryRequirement.builder()
                    .company(company)
                    .trip(trip)
                    .customer(customer)
                    .destination(seed.address())
                    .destinationLat(seed.latitude())
                    .destinationLng(seed.longitude())
                    .deadline(deadline)
                    .priority(DeliveryRequirementPriority.NORMAL)
                    .status(DeliveryRequirementStatus.IN_TRIP)
                    .createdBy(trip.getCreatedByDispatcher())
                    .expectedPackageCount(4)
                    .note("Mock OptimizeService")
                    .build());

            DeliveryStop stop = deliveryStopRepository.save(DeliveryStop.builder()
                    .trip(trip)
                    .deliveryRequirement(requirement)
                    .stopSequence(seed.sequence())
                    .stopName(seed.stopName())
                    .address(seed.address())
                    .latitude(seed.latitude())
                    .longitude(seed.longitude())
                    .status(DeliveryStopStatus.PENDING)
                    .plannedArrival(trip.getDepartureTime().plusHours(seed.sequence()))
                    .build());

            TransportOrder order = orderRepository.save(TransportOrder.builder()
                    .company(company)
                    .customer(customer)
                    .deliveryStop(stop)
                    .orderCode("MOCK-OPT-ORD-" + seed.sequence())
                    .totalWeightKg(new BigDecimal("72.00"))
                    .totalVolumeCbm(new BigDecimal("0.3840"))
                    .status(OrderStatus.ASSIGNED)
                    .build());

            List<CargoPackage> packages = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String code = "MOCK-OPT-PKG-" + String.format("%02d", packageIndex++);
                packages.add(packageRepository.save(CargoPackage.builder()
                        .company(company)
                        .order(order)
                        .packageType(packageType)
                        .packageCode(code)
                        .packageName("Thung nuoc " + code)
                        .trackingBarcode(code)
                        .qrToken(UUID.randomUUID().toString())
                        .handlingClass(HandlingClass.STANDARD)
                        .actualLength(new BigDecimal("60.00"))
                        .actualWidth(new BigDecimal("40.00"))
                        .actualHeight(new BigDecimal("40.00"))
                        .actualWeightKg(new BigDecimal("18.00"))
                        .destination(seed.address())
                        .status(PackageStatus.PLANNED)
                        .isPinned(false)
                        .build()));
            }
            requirement.getPackages().addAll(packages);
            deliveryRequirementRepository.save(requirement);
        }
    }

    private record StopSeed(
            int sequence,
            String stopName,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            String phone
    ) {
    }
}
