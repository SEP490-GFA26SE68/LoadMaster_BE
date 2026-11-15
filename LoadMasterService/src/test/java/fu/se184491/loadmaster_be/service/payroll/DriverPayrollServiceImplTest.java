package fu.se184491.loadmaster_be.service.payroll;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollPeriodResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.payroll.CompanyPayrollRate;
import fu.se184491.loadmaster_be.entity.payroll.TripPayrollDriverShare;
import fu.se184491.loadmaster_be.entity.route.RoutePlan;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.payroll.CompanyPayrollRateRepository;
import fu.se184491.loadmaster_be.repository.payroll.DriverPayrollRepository;
import fu.se184491.loadmaster_be.repository.payroll.PayrollPeriodRepository;
import fu.se184491.loadmaster_be.repository.payroll.TripPayrollDriverShareRepository;
import fu.se184491.loadmaster_be.repository.route.RoutePlanRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.payroll.impl.DriverPayrollServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriverPayrollServiceImplTest {

    @Mock private CompanyPayrollRateRepository rateRepository;
    @Mock private PayrollPeriodRepository periodRepository;
    @Mock private DriverPayrollRepository driverPayrollRepository;
    @Mock private TripPayrollDriverShareRepository shareRepository;
    @Mock private TripRepository tripRepository;
    @Mock private RoutePlanRepository routePlanRepository;
    @Mock private LoadPlanRepository loadPlanRepository;
    @Mock private PackagePlacementRepository packagePlacementRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private DriverPayrollServiceImpl service;

    private Company company;
    private User driverOne;
    private User driverTwo;
    private Trip trip;

    @BeforeEach
    void setUp() {
        company = Company.builder().id(10L).build();
        driverOne = driver(21L, "Tài xế Một", company);
        driverTwo = driver(22L, "Tài xế Hai", company);
        trip = Trip.builder()
                .id(77L)
                .tripCode("TRIP-0077")
                .company(company)
                .driver(driverOne)
                .status(TripStatus.DELIVERED)
                .completedAt(LocalDateTime.of(2026, 11, 12, 12, 0))
                .build();
    }

    @Test
    void calculatePeriodSplitsProductivityBetweenTwoDriversAndKeepsFullBaseSalaryForEach() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        CompanyPayrollRate rate = CompanyPayrollRate.builder()
                .id(5L)
                .company(company)
                .effectiveFrom(periodStart)
                .baseSalaryVnd(new BigDecimal("8000000"))
                .rateVndPerKg(new BigDecimal("100"))
                .rateVndPerKm(new BigDecimal("2000"))
                .build();
        LoadPlan loadPlan = LoadPlan.builder().id(55L).approved(true).build();
        CargoPackage cargoPackage = CargoPackage.builder().actualWeightKg(new BigDecimal("1000")).build();
        PackagePlacement placement = PackagePlacement.builder().cargoPackage(cargoPackage).build();
        RoutePlan routePlan = RoutePlan.builder().id(66L).totalDistanceMeters(120_000).isActive(true).build();
        List<TripPayrollDriverShare> shares = List.of(
                TripPayrollDriverShare.builder().trip(trip).driver(driverOne).sharePercent(new BigDecimal("50.00")).build(),
                TripPayrollDriverShare.builder().trip(trip).driver(driverTwo).sharePercent(new BigDecimal("50.00")).build()
        );

        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, periodStart))
                .thenReturn(Optional.of(rate));
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, periodStart)).thenReturn(Optional.empty());
        when(userRepository.findByCompanyIdAndUserRoleTypeAndStatus(10L, UserRole.DRIVER, UserStatus.ACTIVE))
                .thenReturn(List.of(driverOne, driverTwo));
        when(tripRepository.findByCompanyIdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                10L, TripStatus.DELIVERED, periodStart.atStartOfDay(), periodStart.plusMonths(1).atStartOfDay()))
                .thenReturn(List.of(trip));
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(77L)).thenReturn(Optional.of(loadPlan));
        when(packagePlacementRepository.findByLoadPlanId(55L)).thenReturn(List.of(placement));
        when(routePlanRepository.findByTripIdAndIsActiveTrue(77L)).thenReturn(Optional.of(routePlan));
        when(shareRepository.findByTripId(77L)).thenReturn(shares);

        PayrollPeriodResponse response = service.calculatePeriod(10L, "2026-11");

        assertEquals(2, response.drivers().size());
        assertEquals(new BigDecimal("8170000"), response.drivers().get(0).totalSalaryVnd());
        assertEquals(new BigDecimal("8170000"), response.drivers().get(1).totalSalaryVnd());
        assertEquals(new BigDecimal("500.000"), response.drivers().get(0).totalKg());
        assertEquals(new BigDecimal("60.000"), response.drivers().get(0).totalKm());
        assertEquals(new BigDecimal("50000"), response.drivers().get(0).kgAmountVnd());
        assertEquals(new BigDecimal("120000"), response.drivers().get(0).kmAmountVnd());
    }

    @Test
    void includesInactiveHistoricalCoDriverProductivityWithoutAddingFixedSalary() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        driverTwo.setStatus(UserStatus.INACTIVE);
        CompanyPayrollRate rate = CompanyPayrollRate.builder()
                .company(company)
                .effectiveFrom(periodStart)
                .baseSalaryVnd(new BigDecimal("8000000"))
                .rateVndPerKg(new BigDecimal("100"))
                .rateVndPerKm(new BigDecimal("2000"))
                .build();
        LoadPlan loadPlan = LoadPlan.builder().id(55L).approved(true).build();
        CargoPackage cargoPackage = CargoPackage.builder().actualWeightKg(new BigDecimal("1000")).build();
        PackagePlacement placement = PackagePlacement.builder().cargoPackage(cargoPackage).build();
        RoutePlan routePlan = RoutePlan.builder().id(66L).totalDistanceMeters(120_000).isActive(true).build();
        List<TripPayrollDriverShare> shares = List.of(
                TripPayrollDriverShare.builder().trip(trip).driver(driverOne).sharePercent(new BigDecimal("50.00")).build(),
                TripPayrollDriverShare.builder().trip(trip).driver(driverTwo).sharePercent(new BigDecimal("50.00")).build()
        );

        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, periodStart))
                .thenReturn(Optional.of(rate));
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, periodStart)).thenReturn(Optional.empty());
        when(userRepository.findByCompanyIdAndUserRoleTypeAndStatus(10L, UserRole.DRIVER, UserStatus.ACTIVE))
                .thenReturn(List.of(driverOne));
        when(tripRepository.findByCompanyIdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                10L, TripStatus.DELIVERED, periodStart.atStartOfDay(), periodStart.plusMonths(1).atStartOfDay()))
                .thenReturn(List.of(trip));
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(77L)).thenReturn(Optional.of(loadPlan));
        when(packagePlacementRepository.findByLoadPlanId(55L)).thenReturn(List.of(placement));
        when(routePlanRepository.findByTripIdAndIsActiveTrue(77L)).thenReturn(Optional.of(routePlan));
        when(shareRepository.findByTripId(77L)).thenReturn(shares);

        PayrollPeriodResponse response = service.calculatePeriod(10L, "2026-11");

        assertEquals(new BigDecimal("8170000"), response.drivers().get(0).totalSalaryVnd());
        assertEquals(BigDecimal.ZERO, response.drivers().get(1).baseSalaryVnd());
        assertEquals(new BigDecimal("170000"), response.drivers().get(1).totalSalaryVnd());
        assertEquals(new BigDecimal("500.000"), response.drivers().get(1).totalKg());
    }

    private static User driver(Long id, String name, Company company) {
        return User.builder()
                .id(id)
                .company(company)
                .fullName(name)
                .email(id + "@loadmaster.vn")
                .keycloakId("kc-" + id)
                .userRoleType(UserRole.DRIVER)
                .status(UserStatus.ACTIVE)
                .build();
    }
}
