package fu.se184491.loadmaster_be.service.payroll;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.payroll.CompanyPayrollRateRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.DriverBaseSalaryRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.SetTripPayrollDriversRequest;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollPeriodResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.payroll.CompanyPayrollRate;
import fu.se184491.loadmaster_be.entity.payroll.DriverBaseSalaryOverride;
import fu.se184491.loadmaster_be.entity.payroll.DriverPayroll;
import fu.se184491.loadmaster_be.entity.payroll.PayrollPeriod;
import fu.se184491.loadmaster_be.entity.payroll.TripPayrollDriverShare;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.payroll.CompanyPayrollRateRepository;
import fu.se184491.loadmaster_be.repository.payroll.DriverBaseSalaryOverrideRepository;
import fu.se184491.loadmaster_be.repository.payroll.DriverPayrollRepository;
import fu.se184491.loadmaster_be.repository.payroll.PayrollPeriodRepository;
import fu.se184491.loadmaster_be.repository.payroll.TripPayrollDriverShareRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriverPayrollServiceImplTest {

    @Mock private CompanyPayrollRateRepository rateRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private DriverBaseSalaryOverrideRepository baseSalaryOverrideRepository;
    @Mock private PayrollPeriodRepository periodRepository;
    @Mock private DriverPayrollRepository driverPayrollRepository;
    @Mock private TripPayrollDriverShareRepository shareRepository;
    @Mock private TripRepository tripRepository;
    @Mock private DeliveryStopRepository deliveryStopRepository;
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
        lenient().when(companyRepository.findByIdForPayrollUpdate(10L)).thenReturn(Optional.of(company));
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
    void allowsCurrentMonthRateForNewCompanyWithoutPriorRates() {
        LocalDate currentMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).atDay(1);
        CompanyPayrollRateRequest request = new CompanyPayrollRateRequest(
                currentMonth, new BigDecimal("8000000"), new BigDecimal("100"), new BigDecimal("15"), new BigDecimal("150"), new BigDecimal("85"));
        when(rateRepository.existsByCompanyId(10L)).thenReturn(false);
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, currentMonth)).thenReturn(Optional.empty());
        when(rateRepository.findByCompanyIdAndEffectiveFrom(10L, currentMonth)).thenReturn(Optional.empty());
        when(rateRepository.save(any(CompanyPayrollRate.class)))
                .thenAnswer(invocation -> {
                    CompanyPayrollRate rate = invocation.getArgument(0);
                    rate.setId(1L);
                    return rate;
                });

        var response = service.createRate(10L, request);

        assertNotNull(response);
        assertEquals(currentMonth, response.effectiveFrom());
        assertEquals(new BigDecimal("8000000"), response.baseSalaryVnd());
    }

    @Test
    void rejectsPastMonthRateForNewCompanyWithoutPriorRates() {
        LocalDate pastMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusMonths(1).atDay(1);
        CompanyPayrollRateRequest request = new CompanyPayrollRateRequest(
                pastMonth, new BigDecimal("8000000"), new BigDecimal("100"), new BigDecimal("15"), new BigDecimal("150"), new BigDecimal("85"));
        when(rateRepository.existsByCompanyId(10L)).thenReturn(false);

        AppException error = assertThrows(AppException.class, () -> service.createRate(10L, request));

        assertEquals("PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY", error.getErrorCode().name());
    }

    @Test
    void rejectsCompanyRateEffectiveInCurrentMonthWhenRatesAlreadyExist() {
        LocalDate currentMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).atDay(1);
        CompanyPayrollRateRequest request = new CompanyPayrollRateRequest(
                currentMonth, new BigDecimal("1000000"), new BigDecimal("1000"), new BigDecimal("15"), new BigDecimal("150"), new BigDecimal("85"));
        when(rateRepository.existsByCompanyId(10L)).thenReturn(true);

        AppException error = assertThrows(AppException.class, () -> service.createRate(10L, request));

        assertEquals("PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY", error.getErrorCode().name());
    }

    @Test
    void updatesExistingCurrentMonthRateAndRecalculatesWhilePayrollIsDraft() {
        LocalDate currentMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).atDay(1);
        CompanyPayrollRate existing = CompanyPayrollRate.builder()
                .id(5L).company(company).effectiveFrom(currentMonth)
                .baseSalaryVnd(new BigDecimal("200000"))
                .rateVndPerKg(new BigDecimal("2000"))
                .commissionPercent(new BigDecimal("15")).handlingFeeVndPerKg(new BigDecimal("150")).handlingDriverPercent(new BigDecimal("85"))
                .build();
        PayrollPeriod draft = PayrollPeriod.builder()
                .id(9L).company(company).periodStart(currentMonth).status(PayrollPeriodStatus.DRAFT).build();
        when(rateRepository.existsByCompanyId(10L)).thenReturn(true);
        when(rateRepository.findByCompanyIdAndEffectiveFrom(10L, currentMonth)).thenReturn(Optional.of(existing));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, currentMonth)).thenReturn(Optional.of(draft));
        when(periodRepository.findByCompanyIdAndPayrollRateIdAndStatusOrderByPeriodStartAsc(
                10L, 5L, PayrollPeriodStatus.DRAFT)).thenReturn(List.of(draft));
        when(rateRepository.save(any(CompanyPayrollRate.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, currentMonth))
                .thenReturn(Optional.of(existing));
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, currentMonth)).thenReturn(Optional.of(draft));

        var response = service.createRate(10L, new CompanyPayrollRateRequest(currentMonth,
                new BigDecimal("200000"), new BigDecimal("4000"), new BigDecimal("15"), new BigDecimal("150"), new BigDecimal("85")));

        assertEquals(new BigDecimal("4000"), response.rateVndPerKg());
        assertEquals(PayrollPeriodStatus.DRAFT, draft.getStatus());
        verify(periodRepository).findByCompanyIdAndPeriodStartForUpdate(10L, currentMonth);
    }

    @Test
    void updatesExistingPastMonthRateIfPayrollWasNeverLocked() {
        LocalDate pastMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusMonths(1).atDay(1);
        CompanyPayrollRate existing = CompanyPayrollRate.builder()
                .id(6L).company(company).effectiveFrom(pastMonth)
                .baseSalaryVnd(new BigDecimal("20000"))
                .rateVndPerKg(new BigDecimal("1000"))
                .commissionPercent(new BigDecimal("15")).handlingFeeVndPerKg(new BigDecimal("150")).handlingDriverPercent(new BigDecimal("85"))
                .build();
        when(rateRepository.existsByCompanyId(10L)).thenReturn(true);
        when(rateRepository.findByCompanyIdAndEffectiveFrom(10L, pastMonth)).thenReturn(Optional.of(existing));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, pastMonth)).thenReturn(Optional.empty());
        when(rateRepository.save(any(CompanyPayrollRate.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.createRate(10L, new CompanyPayrollRateRequest(pastMonth,
                new BigDecimal("200000"), new BigDecimal("1000"), new BigDecimal("15"), new BigDecimal("150"), new BigDecimal("85")));

        assertEquals(6L, response.id());
        assertEquals(new BigDecimal("200000"), response.baseSalaryVnd());
    }

    @Test
    void rejectsExistingCurrentMonthRateWhenPayrollIsLocked() {
        LocalDate currentMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).atDay(1);
        CompanyPayrollRate existing = CompanyPayrollRate.builder()
                .id(5L).company(company).effectiveFrom(currentMonth).build();
        when(rateRepository.existsByCompanyId(10L)).thenReturn(true);
        when(rateRepository.findByCompanyIdAndEffectiveFrom(10L, currentMonth)).thenReturn(Optional.of(existing));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, currentMonth))
                .thenReturn(Optional.of(PayrollPeriod.builder().status(PayrollPeriodStatus.LOCKED).build()));

        AppException error = assertThrows(AppException.class, () -> service.createRate(10L,
                new CompanyPayrollRateRequest(currentMonth, new BigDecimal("200000"),
                        new BigDecimal("4000"), new BigDecimal("15"), new BigDecimal("150"), new BigDecimal("85"))));

        assertEquals("PAYROLL_PERIOD_LOCKED", error.getErrorCode().name());
    }

    @Test
    void rejectsExistingRateUsedByALaterLockedPayrollPeriod() {
        LocalDate pastMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusMonths(1).atDay(1);
        CompanyPayrollRate existing = CompanyPayrollRate.builder()
                .id(5L).company(company).effectiveFrom(pastMonth)
                .baseSalaryVnd(new BigDecimal("20000"))
                .rateVndPerKg(new BigDecimal("1000"))
                .commissionPercent(new BigDecimal("15")).handlingFeeVndPerKg(new BigDecimal("150")).handlingDriverPercent(new BigDecimal("85"))
                .build();
        when(rateRepository.existsByCompanyId(10L)).thenReturn(true);
        when(rateRepository.findByCompanyIdAndEffectiveFrom(10L, pastMonth)).thenReturn(Optional.of(existing));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, pastMonth)).thenReturn(Optional.empty());
        when(periodRepository.existsByCompanyIdAndPayrollRateIdAndStatus(10L, 5L, PayrollPeriodStatus.LOCKED))
                .thenReturn(true);

        AppException error = assertThrows(AppException.class, () -> service.createRate(10L,
                new CompanyPayrollRateRequest(pastMonth, new BigDecimal("200000"),
                        new BigDecimal("1000"), new BigDecimal("15"), new BigDecimal("150"), new BigDecimal("85"))));

        assertEquals("PAYROLL_PERIOD_LOCKED", error.getErrorCode().name());
    }

    @Test
    void updatesExistingRateAndRecalculatesAnotherDraftPayrollPeriod() {
        LocalDate month = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).atDay(1);
        LocalDate nextMonth = month.plusMonths(1);
        CompanyPayrollRate existing = CompanyPayrollRate.builder()
                .id(5L).company(company).effectiveFrom(month)
                .baseSalaryVnd(new BigDecimal("20000"))
                .rateVndPerKg(new BigDecimal("1000"))
                .commissionPercent(new BigDecimal("15")).handlingFeeVndPerKg(new BigDecimal("150")).handlingDriverPercent(new BigDecimal("85"))
                .build();
        DriverPayroll oldPayroll = DriverPayroll.builder().driver(driverOne)
                .baseSalaryVnd(new BigDecimal("20000"))
                .totalKg(BigDecimal.ZERO).kgAmountVnd(BigDecimal.ZERO)
                .handlingAmountVnd(BigDecimal.ZERO)
                .totalSalaryVnd(new BigDecimal("20000")).build();
        PayrollPeriod laterDraft = PayrollPeriod.builder().id(99L).company(company)
                .payrollRate(existing).periodStart(nextMonth).status(PayrollPeriodStatus.DRAFT)
                .driverPayrolls(new java.util.ArrayList<>(List.of(oldPayroll))).build();
        when(rateRepository.existsByCompanyId(10L)).thenReturn(true);
        when(rateRepository.findByCompanyIdAndEffectiveFrom(10L, month)).thenReturn(Optional.of(existing));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, month)).thenReturn(Optional.empty());
        when(periodRepository.findByCompanyIdAndPayrollRateIdAndStatusOrderByPeriodStartAsc(
                10L, 5L, PayrollPeriodStatus.DRAFT)).thenReturn(List.of(laterDraft));
        when(rateRepository.save(any(CompanyPayrollRate.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, nextMonth))
                .thenReturn(Optional.of(existing));
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, nextMonth))
                .thenReturn(Optional.of(laterDraft));
        when(userRepository.findByCompanyIdAndUserRoleTypeAndStatus(10L, UserRole.DRIVER, UserStatus.ACTIVE))
                .thenReturn(List.of(driverOne));
        when(tripRepository.findByCompanyIdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                10L, TripStatus.DELIVERED, nextMonth.atStartOfDay(), nextMonth.plusMonths(1).atStartOfDay()))
                .thenReturn(List.of());

        var response = service.createRate(10L,
                new CompanyPayrollRateRequest(month, new BigDecimal("30000"),
                        new BigDecimal("1000"), new BigDecimal("15"), new BigDecimal("150"), new BigDecimal("85")));

        assertEquals(new BigDecimal("30000"), response.baseSalaryVnd());
        assertEquals(new BigDecimal("30000"), laterDraft.getDriverPayrolls().get(0).getTotalSalaryVnd());
    }

    @Test
    void deletesOnlyCompanyDraftPayroll() {
        LocalDate month = LocalDate.of(2026, 11, 1);
        PayrollPeriod draft = PayrollPeriod.builder().id(44L).company(company)
                .periodStart(month).status(PayrollPeriodStatus.DRAFT).build();
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, month))
                .thenReturn(Optional.of(draft));

        service.deleteDraftPeriod(10L, "2026-11");

        verify(periodRepository).delete(draft);
    }

    @Test
    void refusesToDeleteLockedPayroll() {
        LocalDate month = LocalDate.of(2026, 11, 1);
        PayrollPeriod locked = PayrollPeriod.builder().id(44L).company(company)
                .periodStart(month).status(PayrollPeriodStatus.LOCKED).build();
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, month))
                .thenReturn(Optional.of(locked));

        AppException error = assertThrows(AppException.class,
                () -> service.deleteDraftPeriod(10L, "2026-11"));

        assertEquals("PAYROLL_PERIOD_LOCKED", error.getErrorCode().name());
        verify(periodRepository, never()).delete(any(PayrollPeriod.class));
    }

    @Test
    void refusesToDeleteAnotherCompanyPayroll() {
        LocalDate month = LocalDate.of(2026, 11, 1);
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, month))
                .thenReturn(Optional.empty());

        AppException error = assertThrows(AppException.class,
                () -> service.deleteDraftPeriod(10L, "2026-11"));

        assertEquals("PAYROLL_PERIOD_NOT_FOUND", error.getErrorCode().name());
        verify(periodRepository, never()).delete(any(PayrollPeriod.class));
    }

    @Test
    void updatesExistingRateWhenPeriodIsNotLocked() {
        LocalDate nextMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusMonths(1).atDay(1);
        CompanyPayrollRate existing = CompanyPayrollRate.builder()
                .id(5L)
                .company(company)
                .effectiveFrom(nextMonth)
                .baseSalaryVnd(new BigDecimal("8000000"))
                .rateVndPerKg(new BigDecimal("100"))
                .commissionPercent(new BigDecimal("15")).handlingFeeVndPerKg(new BigDecimal("150")).handlingDriverPercent(new BigDecimal("85"))
                .build();
        when(rateRepository.existsByCompanyId(10L)).thenReturn(true);
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, nextMonth)).thenReturn(Optional.empty());
        when(rateRepository.findByCompanyIdAndEffectiveFrom(10L, nextMonth)).thenReturn(Optional.of(existing));
        when(rateRepository.save(any(CompanyPayrollRate.class))).thenAnswer(inv -> inv.getArgument(0));

        CompanyPayrollRateRequest updateRequest = new CompanyPayrollRateRequest(
                nextMonth, new BigDecimal("9000000"), new BigDecimal("150"), new BigDecimal("15"), new BigDecimal("150"), new BigDecimal("85"));

        var response = service.createRate(10L, updateRequest);

        assertNotNull(response);
        assertEquals(5L, response.id());
        assertEquals(new BigDecimal("9000000"), response.baseSalaryVnd());
        assertEquals(new BigDecimal("150"), response.rateVndPerKg());
        assertEquals(new BigDecimal("15"), response.commissionPercent());
    }

    @Test
    void rejectsRateUpdateWhenPeriodIsLocked() {
        LocalDate nextMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusMonths(1).atDay(1);
        PayrollPeriod lockedPeriod = PayrollPeriod.builder()
                .status(PayrollPeriodStatus.LOCKED)
                .build();
        when(rateRepository.existsByCompanyId(10L)).thenReturn(true);
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, nextMonth)).thenReturn(Optional.of(lockedPeriod));

        CompanyPayrollRateRequest updateRequest = new CompanyPayrollRateRequest(
                nextMonth, new BigDecimal("9000000"), new BigDecimal("150"), new BigDecimal("15"), new BigDecimal("150"), new BigDecimal("85"));

        AppException error = assertThrows(AppException.class, () -> service.createRate(10L, updateRequest));

        assertEquals("PAYROLL_PERIOD_LOCKED", error.getErrorCode().name());
    }

    @Test
    void rejectsDriverBaseSalaryEffectiveInCurrentMonth() {
        LocalDate currentMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).atDay(1);
        DriverBaseSalaryRequest request = new DriverBaseSalaryRequest(currentMonth, new BigDecimal("9000000"));

        AppException error = assertThrows(AppException.class,
                () -> service.setDriverBaseSalary(10L, 21L, request));

        assertEquals("PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY", error.getErrorCode().name());
    }

    @Test
    void savesNextMonthBaseSalaryForCompanyDriver() {
        LocalDate nextMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusMonths(1).atDay(1);
        DriverBaseSalaryRequest request = new DriverBaseSalaryRequest(nextMonth, new BigDecimal("9000000"));
        when(userRepository.findById(21L)).thenReturn(Optional.of(driverOne));
        when(baseSalaryOverrideRepository
                .findFirstByDriverIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(21L, nextMonth))
                .thenReturn(Optional.empty());
        when(baseSalaryOverrideRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.setDriverBaseSalary(10L, 21L, request);

        assertNotNull(response);
        assertEquals(21L, response.driverId());
        assertEquals(new BigDecimal("9000000"), response.baseSalaryVnd());
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
                .commissionPercent(new BigDecimal("15")).handlingFeeVndPerKg(new BigDecimal("150")).handlingDriverPercent(new BigDecimal("85"))
                .build();
        LoadPlan loadPlan = LoadPlan.builder().id(55L).approved(true).build();
        CargoPackage cargoPackage = CargoPackage.builder().actualWeightKg(new BigDecimal("1000")).build();
        PackagePlacement placement = PackagePlacement.builder().cargoPackage(cargoPackage).build();
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
        when(shareRepository.findByTripId(77L)).thenReturn(shares);

        PayrollPeriodResponse response = service.calculatePeriod(10L, "2026-11");

        assertEquals(2, response.drivers().size());
        // 1000 kg × 100 đ × 15% = 15.000 hoa hồng; 1000 kg × 150 đ × 85% = 127.500 bốc dỡ; chia đôi hai tài xế.
        assertEquals(new BigDecimal("8071250"), response.drivers().get(0).totalSalaryVnd());
        assertEquals(new BigDecimal("8071250"), response.drivers().get(1).totalSalaryVnd());
        assertEquals(new BigDecimal("500.000"), response.drivers().get(0).totalKg());
        assertEquals(new BigDecimal("7500"), response.drivers().get(0).kgAmountVnd());
        assertEquals(new BigDecimal("63750"), response.drivers().get(0).handlingAmountVnd());
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
                .commissionPercent(new BigDecimal("15")).handlingFeeVndPerKg(new BigDecimal("150")).handlingDriverPercent(new BigDecimal("85"))
                .build();
        LoadPlan loadPlan = LoadPlan.builder().id(55L).approved(true).build();
        CargoPackage cargoPackage = CargoPackage.builder().actualWeightKg(new BigDecimal("1000")).build();
        PackagePlacement placement = PackagePlacement.builder().cargoPackage(cargoPackage).build();
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
        when(shareRepository.findByTripId(77L)).thenReturn(shares);

        PayrollPeriodResponse response = service.calculatePeriod(10L, "2026-11");

        assertEquals(new BigDecimal("8071250"), response.drivers().get(0).totalSalaryVnd());
        assertEquals(BigDecimal.ZERO, response.drivers().get(1).baseSalaryVnd());
        assertEquals(new BigDecimal("71250"), response.drivers().get(1).totalSalaryVnd());
        assertEquals(new BigDecimal("500.000"), response.drivers().get(1).totalKg());
    }

    @Test
    void calculatePeriodUsesDriverBaseSalaryOverrideInsteadOfCompanyRate() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        CompanyPayrollRate rate = CompanyPayrollRate.builder()
                .company(company)
                .effectiveFrom(periodStart)
                .baseSalaryVnd(new BigDecimal("8000000"))
                .rateVndPerKg(new BigDecimal("100"))
                .commissionPercent(new BigDecimal("15")).handlingFeeVndPerKg(new BigDecimal("150")).handlingDriverPercent(new BigDecimal("85"))
                .build();
        DriverBaseSalaryOverride override = DriverBaseSalaryOverride.builder()
                .driver(driverOne)
                .effectiveFrom(periodStart)
                .baseSalaryVnd(new BigDecimal("12000000"))
                .build();

        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, periodStart))
                .thenReturn(Optional.of(rate));
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, periodStart)).thenReturn(Optional.empty());
        when(userRepository.findByCompanyIdAndUserRoleTypeAndStatus(10L, UserRole.DRIVER, UserStatus.ACTIVE))
                .thenReturn(List.of(driverOne));
        when(tripRepository.findByCompanyIdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                10L, TripStatus.DELIVERED, periodStart.atStartOfDay(), periodStart.plusMonths(1).atStartOfDay()))
                .thenReturn(List.of());
        when(baseSalaryOverrideRepository
                .findFirstByDriverIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(21L, periodStart))
                .thenReturn(Optional.of(override));

        PayrollPeriodResponse response = service.calculatePeriod(10L, "2026-11");

        assertEquals(1, response.drivers().size());
        assertEquals(new BigDecimal("12000000"), response.drivers().get(0).baseSalaryVnd());
        assertEquals(new BigDecimal("12000000"), response.drivers().get(0).totalSalaryVnd());
    }

    @Test
    void getPeriodUsesNonLockingFindPeriod() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        PayrollPeriod period = PayrollPeriod.builder()
                .id(1L)
                .periodStart(periodStart)
                .status(PayrollPeriodStatus.DRAFT)
                .driverPayrolls(List.of())
                .build();
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, periodStart))
                .thenReturn(Optional.of(period));

        var response = service.getPeriod(10L, "2026-11");

        assertNotNull(response);
        assertEquals("2026-11", response.period());
        verify(periodRepository).findByCompanyIdAndPeriodStart(10L, periodStart);
        verify(periodRepository, never()).findByCompanyIdAndPeriodStartForUpdate(any(), any());
    }

    @Test
    void recalculatePeriodFlushesOrphanedDriverPayrollsBeforeReinserting() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        CompanyPayrollRate rate = CompanyPayrollRate.builder()
                .id(5L)
                .company(company)
                .effectiveFrom(periodStart)
                .baseSalaryVnd(new BigDecimal("8000000"))
                .rateVndPerKg(new BigDecimal("100"))
                .commissionPercent(new BigDecimal("15")).handlingFeeVndPerKg(new BigDecimal("150")).handlingDriverPercent(new BigDecimal("85"))
                .build();
        DriverPayroll existingDriverPayroll = DriverPayroll.builder()
                .id(101L)
                .driver(driverOne)
                .baseSalaryVnd(new BigDecimal("8000000"))
                .totalKg(BigDecimal.ZERO)
                .kgAmountVnd(BigDecimal.ZERO)
                .handlingAmountVnd(BigDecimal.ZERO)
                .totalSalaryVnd(new BigDecimal("8000000"))
                .build();
        PayrollPeriod existingPeriod = PayrollPeriod.builder()
                .id(99L)
                .company(company)
                .payrollRate(rate)
                .periodStart(periodStart)
                .status(PayrollPeriodStatus.DRAFT)
                .driverPayrolls(new java.util.ArrayList<>(List.of(existingDriverPayroll)))
                .build();

        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, periodStart))
                .thenReturn(Optional.of(rate));
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, periodStart))
                .thenReturn(Optional.of(existingPeriod));
        when(userRepository.findByCompanyIdAndUserRoleTypeAndStatus(10L, UserRole.DRIVER, UserStatus.ACTIVE))
                .thenReturn(List.of(driverOne));
        when(tripRepository.findByCompanyIdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                10L, TripStatus.DELIVERED, periodStart.atStartOfDay(), periodStart.plusMonths(1).atStartOfDay()))
                .thenReturn(List.of());

        var response = service.calculatePeriod(10L, "2026-11");

        assertNotNull(response);
        verify(periodRepository).flush();
    }

    @Test
    void setTripDriversFlushesDeletedSharesBeforeInserting() {
        when(tripRepository.findByIdAndCompanyId(77L, 10L)).thenReturn(Optional.of(trip));
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, LocalDate.of(2026, 11, 1))).thenReturn(Optional.empty());
        when(userRepository.findAllById(List.of(21L, 22L))).thenReturn(List.of(driverOne, driverTwo));
        when(shareRepository.save(any(TripPayrollDriverShare.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.setTripDrivers(10L, 77L, new SetTripPayrollDriversRequest(List.of(21L, 22L)));

        assertEquals(2, response.size());
        verify(shareRepository).deleteByTripId(77L);
        verify(shareRepository).flush();
    }

    @Test
    void lockPeriodSetsLockedAtWithoutFractionalSeconds() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        PayrollPeriod period = PayrollPeriod.builder()
                .id(1L)
                .periodStart(periodStart)
                .status(PayrollPeriodStatus.DRAFT)
                .driverPayrolls(List.of())
                .build();
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, periodStart))
                .thenReturn(Optional.of(period));
        when(periodRepository.save(any(PayrollPeriod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.lockPeriod(10L, "2026-11");

        assertNotNull(response);
        assertEquals(PayrollPeriodStatus.LOCKED, response.status());
        assertEquals(PayrollPeriodStatus.LOCKED, period.getStatus());
        assertNotNull(period.getLockedAt());
        assertEquals(0, period.getLockedAt().getNano(), "lockedAt must not have fractional seconds");
        verify(periodRepository).save(period);
    }

    @Test
    void calculatesLegacyDeliveredTripsWithNullCompletedAtUsingStopArrival() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        LocalDateTime stopArrival = LocalDateTime.of(2026, 11, 6, 16, 6);
        CompanyPayrollRate rate = CompanyPayrollRate.builder()
                .id(1L)
                .company(company)
                .effectiveFrom(periodStart)
                .baseSalaryVnd(new BigDecimal("1000000"))
                .rateVndPerKg(new BigDecimal("100"))
                .commissionPercent(new BigDecimal("15")).handlingFeeVndPerKg(new BigDecimal("150")).handlingDriverPercent(new BigDecimal("85"))
                .build();
        Trip legacyTrip = Trip.builder()
                .id(88L)
                .tripCode("TRIP-LEGACY-88")
                .company(company)
                .driver(driverOne)
                .status(TripStatus.DELIVERED)
                .completedAt(null)
                .build();
        DeliveryStop stop = DeliveryStop.builder()
                .id(501L)
                .trip(legacyTrip)
                .stopSequence(1)
                .actualArrival(stopArrival)
                .build();
        LoadPlan loadPlan = LoadPlan.builder().id(901L).approved(true).build();
        CargoPackage pkg = CargoPackage.builder().id(1001L).actualWeightKg(new BigDecimal("50.000")).build();
        PackagePlacement placement = PackagePlacement.builder().id(1101L).loadPlan(loadPlan).cargoPackage(pkg).build();

        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, periodStart))
                .thenReturn(Optional.of(rate));
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, periodStart)).thenReturn(Optional.empty());
        when(userRepository.findByCompanyIdAndUserRoleTypeAndStatus(10L, UserRole.DRIVER, UserStatus.ACTIVE))
                .thenReturn(List.of(driverOne));
        when(tripRepository.findByCompanyIdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                10L, TripStatus.DELIVERED, periodStart.atStartOfDay(), periodStart.plusMonths(1).atStartOfDay()))
                .thenReturn(List.of());
        when(tripRepository.findByCompanyIdAndStatusAndCompletedAtIsNull(10L, TripStatus.DELIVERED))
                .thenReturn(List.of(legacyTrip));
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(88L)).thenReturn(List.of(stop));
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(88L)).thenReturn(Optional.of(loadPlan));
        when(packagePlacementRepository.findByLoadPlanId(901L)).thenReturn(List.of(placement));
        when(shareRepository.findByTripId(88L)).thenReturn(List.of());

        PayrollPeriodResponse response = service.calculatePeriod(10L, "2026-11");

        assertEquals(stopArrival, legacyTrip.getCompletedAt());
        verify(tripRepository).save(legacyTrip);
        assertEquals(1, response.drivers().size());
        assertEquals(0, new BigDecimal("50.000").compareTo(response.drivers().get(0).totalKg()));
        // base 1.000.000 + 50kg*100*15% (750) + 50kg*150*85% (6.375) = 1.007.125
        assertEquals(0, new BigDecimal("1007125").compareTo(response.drivers().get(0).totalSalaryVnd()));
    }

    @Test
    void driverSeesOnlyBaseSalaryWhilePeriodIsStillDraft() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        CompanyPayrollRate rate = CompanyPayrollRate.builder()
                .id(5L).company(company).effectiveFrom(periodStart)
                .baseSalaryVnd(new BigDecimal("15000000"))
                .rateVndPerKg(new BigDecimal("40000"))
                .commissionPercent(new BigDecimal("15")).handlingFeeVndPerKg(new BigDecimal("150")).handlingDriverPercent(new BigDecimal("85"))
                .build();
        PayrollPeriod draft = PayrollPeriod.builder().id(9L).company(company).periodStart(periodStart)
                .payrollRate(rate).status(PayrollPeriodStatus.DRAFT).build();
        when(userRepository.findById(21L)).thenReturn(Optional.of(driverOne));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, periodStart)).thenReturn(Optional.of(draft));
        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, periodStart))
                .thenReturn(Optional.of(rate));

        var statement = service.getDriverStatement(10L, 21L, "2026-11");

        assertEquals(false, statement.calculated());
        assertEquals(PayrollPeriodStatus.DRAFT, statement.status());
        assertEquals(new BigDecimal("15000000"), statement.baseSalaryVnd());
        assertEquals(null, statement.kgAmountVnd());
        assertEquals(null, statement.handlingAmountVnd());
        assertEquals(null, statement.totalSalaryVnd());
        assertEquals(0, statement.trips().size());
        verify(driverPayrollRepository, never()).findByPayrollPeriodIdAndDriverId(any(), any());
    }

    @Test
    void driverSeesBaseSalaryEvenBeforeAnyPeriodExistsUsingTheirOwnRaise() {
        LocalDate periodStart = LocalDate.of(2026, 12, 1);
        CompanyPayrollRate rate = CompanyPayrollRate.builder()
                .id(5L).company(company).effectiveFrom(LocalDate.of(2026, 11, 1))
                .baseSalaryVnd(new BigDecimal("15000000"))
                .build();
        DriverBaseSalaryOverride raise = DriverBaseSalaryOverride.builder()
                .company(company).driver(driverOne).effectiveFrom(periodStart)
                .baseSalaryVnd(new BigDecimal("16000000")).build();
        when(userRepository.findById(21L)).thenReturn(Optional.of(driverOne));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, periodStart)).thenReturn(Optional.empty());
        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, periodStart))
                .thenReturn(Optional.of(rate));
        when(baseSalaryOverrideRepository
                .findFirstByDriverIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(21L, periodStart))
                .thenReturn(Optional.of(raise));

        var statement = service.getDriverStatement(10L, 21L, "2026-12");

        assertEquals(false, statement.calculated());
        assertEquals(null, statement.status());
        assertEquals(new BigDecimal("16000000"), statement.baseSalaryVnd());
    }

    @Test
    void driverSeesNothingWhenCompanyHasNoRateForThatMonth() {
        LocalDate periodStart = LocalDate.of(2026, 10, 1);
        when(userRepository.findById(21L)).thenReturn(Optional.of(driverOne));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, periodStart)).thenReturn(Optional.empty());
        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, periodStart))
                .thenReturn(Optional.empty());

        var statement = service.getDriverStatement(10L, 21L, "2026-10");

        assertEquals(false, statement.calculated());
        assertEquals(null, statement.baseSalaryVnd());
    }

    @Test
    void driverSeesCommissionAndHandlingOnceThePeriodIsLocked() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        CompanyPayrollRate rate = CompanyPayrollRate.builder()
                .id(5L).company(company).effectiveFrom(periodStart)
                .baseSalaryVnd(new BigDecimal("15000000"))
                .rateVndPerKg(new BigDecimal("40000"))
                .commissionPercent(new BigDecimal("15")).handlingFeeVndPerKg(new BigDecimal("150")).handlingDriverPercent(new BigDecimal("85"))
                .build();
        PayrollPeriod locked = PayrollPeriod.builder().id(9L).company(company).periodStart(periodStart)
                .payrollRate(rate).status(PayrollPeriodStatus.LOCKED).build();
        DriverPayroll payroll = DriverPayroll.builder()
                .payrollPeriod(locked).driver(driverOne)
                .baseSalaryVnd(new BigDecimal("15000000"))
                .totalKg(new BigDecimal("100.000"))
                .kgAmountVnd(new BigDecimal("600000"))
                .handlingAmountVnd(new BigDecimal("12750"))
                .totalSalaryVnd(new BigDecimal("15612750"))
                .trips(new java.util.ArrayList<>())
                .build();
        when(userRepository.findById(21L)).thenReturn(Optional.of(driverOne));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, periodStart)).thenReturn(Optional.of(locked));
        when(driverPayrollRepository.findByPayrollPeriodIdAndDriverId(9L, 21L)).thenReturn(Optional.of(payroll));

        var statement = service.getDriverStatement(10L, 21L, "2026-11");

        assertEquals(true, statement.calculated());
        assertEquals(new BigDecimal("600000"), statement.kgAmountVnd());
        assertEquals(new BigDecimal("12750"), statement.handlingAmountVnd());
        assertEquals(new BigDecimal("15612750"), statement.totalSalaryVnd());
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
