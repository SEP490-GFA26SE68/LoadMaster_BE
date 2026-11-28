package fu.se184491.loadmaster_be.service.payroll;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.dto.request.payroll.CompanyWarehousePayrollRateRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.WarehouseBaseSalaryRequest;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollPeriodResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollStatementResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.payroll.CompanyWarehousePayrollRate;
import fu.se184491.loadmaster_be.entity.payroll.WarehouseBaseSalaryOverride;
import fu.se184491.loadmaster_be.entity.payroll.WarehousePayroll;
import fu.se184491.loadmaster_be.entity.payroll.WarehousePayrollPeriod;
import fu.se184491.loadmaster_be.entity.payroll.WarehousePayrollTripLine;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.warehouse.PlacementConfirmation;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.payroll.CompanyWarehousePayrollRateRepository;
import fu.se184491.loadmaster_be.repository.payroll.WarehouseBaseSalaryOverrideRepository;
import fu.se184491.loadmaster_be.repository.payroll.WarehousePayrollPeriodRepository;
import fu.se184491.loadmaster_be.repository.payroll.WarehousePayrollRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.service.payroll.impl.WarehousePayrollServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehousePayrollServiceImplTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);

    @Mock private CompanyWarehousePayrollRateRepository rateRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private WarehouseBaseSalaryOverrideRepository baseSalaryOverrideRepository;
    @Mock private WarehousePayrollPeriodRepository periodRepository;
    @Mock private WarehousePayrollRepository workerPayrollRepository;
    @Mock private PlacementConfirmationRepository placementConfirmationRepository;
    @Mock private UserRepository userRepository;
    @Mock private BrevoClient brevoClient;

    @InjectMocks private WarehousePayrollServiceImpl service;

    private Company company;
    private User workerA;
    private User workerB;
    private CompanyWarehousePayrollRate rate;
    private Trip tripOne;
    private Trip tripTwo;

    @BeforeEach
    void setUp() {
        company = Company.builder().id(10L).companyName("Long Bình").build();
        lenient().when(companyRepository.findByIdForPayrollUpdate(10L)).thenReturn(Optional.of(company));
        workerA = worker(31L, "Kho Một", UserStatus.ACTIVE);
        workerB = worker(32L, "Kho Hai", UserStatus.ACTIVE);
        rate = CompanyWarehousePayrollRate.builder()
                .id(5L).company(company).effectiveFrom(OCTOBER)
                .baseSalaryVnd(new BigDecimal("5000000.00"))
                .rateVndPerKg(new BigDecimal("150.50"))
                .kpiTargetKg(new BigDecimal("20.000"))
                .kpiBonusVnd(new BigDecimal("300000.00"))
                .build();
        tripOne = Trip.builder().id(71L).tripCode("TRIP-0071").company(company).build();
        tripTwo = Trip.builder().id(72L).tripCode("TRIP-0072").company(company).build();
        lenient().when(baseSalaryOverrideRepository
                        .findFirstByWorkerIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        lenient().when(periodRepository.save(any(WarehousePayrollPeriod.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void calculatesBasePlusKgTimesRatePlusKpiAndOneLinePerTrip() {
        givenOctoberRateAndNewPeriod();
        when(userRepository.findByCompanyIdAndUserRoleTypeAndStatus(10L, UserRole.WAREHOUSE_WORKER, UserStatus.ACTIVE))
                .thenReturn(List.of(workerB, workerA));
        when(placementConfirmationRepository.findValidWarehouseLoadings(10L,
                OCTOBER.atStartOfDay(), LocalDate.of(2026, 11, 1).atStartOfDay()))
                .thenReturn(List.of(
                        loading(workerA, tripOne, actual("12.5"), LocalDateTime.of(2026, 10, 5, 8, 0)),
                        loading(workerA, tripOne, typed("7.25"), LocalDateTime.of(2026, 10, 6, 9, 30)),
                        loading(workerA, tripTwo, actual("3"), LocalDateTime.of(2026, 10, 10, 14, 0))));

        WarehousePayrollPeriodResponse response = service.calculatePeriod(10L, "2026-10");

        assertEquals("2026-10", response.period());
        assertEquals(PayrollPeriodStatus.DRAFT, response.status());
        assertEquals(List.of(31L, 32L), response.workers().stream().map(w -> w.workerId()).toList());
        var a = response.workers().get(0);
        assertEquals(new BigDecimal("5000000"), a.baseSalaryVnd());
        assertEquals(3, a.loadedPackages());
        assertEquals(new BigDecimal("22.750"), a.loadedKg());
        // 22,75 kg × 150,50 đ = 3.423,875 đ → 3.424 đ.
        assertEquals(new BigDecimal("3424"), a.loadedAmountVnd());
        assertTrue(a.kpiAchieved());
        assertEquals(new BigDecimal("300000"), a.kpiBonusVnd());
        assertEquals(new BigDecimal("5303424"), a.totalSalaryVnd());

        var b = response.workers().get(1);
        assertEquals(0, b.loadedPackages());
        assertEquals(0, b.loadedKg().compareTo(BigDecimal.ZERO));
        assertFalse(b.kpiAchieved());
        assertEquals(new BigDecimal("5000000"), b.totalSalaryVnd());

        WarehousePayroll savedA = savedPeriod().getWorkerPayrolls().stream()
                .filter(payroll -> payroll.getWorker().getId() == 31L).findFirst().orElseThrow();
        assertEquals(2, savedA.getTrips().size());
        WarehousePayrollTripLine first = savedA.getTrips().get(0);
        assertEquals(71L, first.getTrip().getId());
        assertEquals(2, first.getLoadedPackages());
        assertEquals(new BigDecimal("19.750"), first.getLoadedKg());
        assertEquals(LocalDate.of(2026, 10, 6), first.getLoadedDate());
    }

    @Test
    void kpiNotReachedPaysNoBonus() {
        givenOctoberRateAndNewPeriod();
        when(userRepository.findByCompanyIdAndUserRoleTypeAndStatus(10L, UserRole.WAREHOUSE_WORKER, UserStatus.ACTIVE))
                .thenReturn(List.of(workerA));
        when(placementConfirmationRepository.findValidWarehouseLoadings(eq(10L), any(), any()))
                .thenReturn(List.of(loading(workerA, tripOne, actual("19.999"), LocalDateTime.of(2026, 10, 5, 8, 0))));

        var a = service.calculatePeriod(10L, "2026-10").workers().get(0);

        assertFalse(a.kpiAchieved());
        assertEquals(new BigDecimal("0"), a.kpiBonusVnd());
        // 19,999 × 150,50 = 3.009,8495 → 3.010 đ.
        assertEquals(new BigDecimal("5003010"), a.totalSalaryVnd());
    }

    @Test
    void workerWhoLeftKeepsLoadedKgButGetsNoBaseSalary() {
        givenOctoberRateAndNewPeriod();
        User left = worker(33L, "Đã nghỉ", UserStatus.INACTIVE);
        when(userRepository.findByCompanyIdAndUserRoleTypeAndStatus(10L, UserRole.WAREHOUSE_WORKER, UserStatus.ACTIVE))
                .thenReturn(List.of());
        when(placementConfirmationRepository.findValidWarehouseLoadings(eq(10L), any(), any()))
                .thenReturn(List.of(loading(left, tripOne, actual("10"), LocalDateTime.of(2026, 10, 5, 8, 0))));

        var line = service.calculatePeriod(10L, "2026-10").workers().get(0);

        assertEquals(33L, line.workerId());
        assertEquals(new BigDecimal("0"), line.baseSalaryVnd());
        assertEquals(new BigDecimal("1505"), line.totalSalaryVnd());
    }

    @Test
    void higherWorkerBaseSalaryWinsAndLowerOneIsIgnored() {
        givenOctoberRateAndNewPeriod();
        when(userRepository.findByCompanyIdAndUserRoleTypeAndStatus(10L, UserRole.WAREHOUSE_WORKER, UserStatus.ACTIVE))
                .thenReturn(List.of(workerA, workerB));
        when(baseSalaryOverrideRepository.findFirstByWorkerIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(31L, OCTOBER))
                .thenReturn(Optional.of(override(workerA, "6000000")));
        when(baseSalaryOverrideRepository.findFirstByWorkerIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(32L, OCTOBER))
                .thenReturn(Optional.of(override(workerB, "4000000")));
        when(placementConfirmationRepository.findValidWarehouseLoadings(eq(10L), any(), any())).thenReturn(List.of());

        var workers = service.calculatePeriod(10L, "2026-10").workers();

        assertEquals(new BigDecimal("6000000"), workers.get(0).baseSalaryVnd());
        assertEquals(new BigDecimal("5000000"), workers.get(1).baseSalaryVnd());
    }

    @Test
    void recalculatingLockedPeriodIsRejected() {
        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, OCTOBER))
                .thenReturn(Optional.of(rate));
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, OCTOBER))
                .thenReturn(Optional.of(period(PayrollPeriodStatus.LOCKED)));

        AppException error = assertThrows(AppException.class, () -> service.calculatePeriod(10L, "2026-10"));

        assertEquals(ErrorCode.PAYROLL_PERIOD_LOCKED, error.getErrorCode());
        verify(placementConfirmationRepository, never()).findValidWarehouseLoadings(anyLong(), any(), any());
    }

    @Test
    void calculatingWithoutRateIsRejected() {
        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, OCTOBER))
                .thenReturn(Optional.empty());

        AppException error = assertThrows(AppException.class, () -> service.calculatePeriod(10L, "2026-10"));

        assertEquals(ErrorCode.PAYROLL_RATE_NOT_CONFIGURED, error.getErrorCode());
    }

    @Test
    void packageWithoutAnyWeightIsIncompleteData() {
        givenOctoberRateAndNewPeriod();
        when(userRepository.findByCompanyIdAndUserRoleTypeAndStatus(10L, UserRole.WAREHOUSE_WORKER, UserStatus.ACTIVE))
                .thenReturn(List.of(workerA));
        when(placementConfirmationRepository.findValidWarehouseLoadings(eq(10L), any(), any()))
                .thenReturn(List.of(loading(workerA, tripOne, CargoPackage.builder().id(900L).build(),
                        LocalDateTime.of(2026, 10, 5, 8, 0))));

        AppException error = assertThrows(AppException.class, () -> service.calculatePeriod(10L, "2026-10"));

        assertEquals(ErrorCode.PAYROLL_DATA_INCOMPLETE, error.getErrorCode());
    }

    @Test
    void invalidPeriodFormatIsRejected() {
        AppException error = assertThrows(AppException.class, () -> service.calculatePeriod(10L, "2026-1"));

        assertEquals(ErrorCode.PAYROLL_INVALID_PERIOD, error.getErrorCode());
    }

    @Test
    void workerSeesOnlyBaseSalaryBeforeLock() {
        when(userRepository.findById(31L)).thenReturn(Optional.of(workerA));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, OCTOBER))
                .thenReturn(Optional.of(period(PayrollPeriodStatus.DRAFT)));
        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, OCTOBER))
                .thenReturn(Optional.of(rate));

        WarehousePayrollStatementResponse statement = service.getWorkerStatement(10L, 31L, "2026-10");

        assertFalse(statement.calculated());
        assertEquals(PayrollPeriodStatus.DRAFT, statement.status());
        assertEquals(new BigDecimal("5000000"), statement.baseSalaryVnd());
        assertNull(statement.loadedKg());
        assertNull(statement.loadedAmountVnd());
        assertNull(statement.kpiAchieved());
        assertNull(statement.totalSalaryVnd());
        assertTrue(statement.trips().isEmpty());
        verify(workerPayrollRepository, never()).findByPayrollPeriodIdAndWorkerId(anyLong(), anyLong());
    }

    @Test
    void workerSeesFullStatementAfterLock() {
        WarehousePayrollPeriod locked = period(PayrollPeriodStatus.LOCKED);
        WarehousePayroll payroll = WarehousePayroll.builder()
                .id(400L).payrollPeriod(locked).worker(workerA)
                .baseSalaryVnd(new BigDecimal("5000000")).loadedPackages(3).loadedKg(new BigDecimal("22.750"))
                .loadedAmountVnd(new BigDecimal("3424")).kpiAchieved(true).kpiBonusVnd(new BigDecimal("300000"))
                .totalSalaryVnd(new BigDecimal("5303424"))
                .build();
        payroll.getTrips().add(WarehousePayrollTripLine.builder().workerPayroll(payroll).trip(tripTwo)
                .loadedDate(LocalDate.of(2026, 10, 10)).loadedPackages(1).loadedKg(new BigDecimal("3.000")).build());
        payroll.getTrips().add(WarehousePayrollTripLine.builder().workerPayroll(payroll).trip(tripOne)
                .loadedDate(LocalDate.of(2026, 10, 6)).loadedPackages(2).loadedKg(new BigDecimal("19.750")).build());
        when(userRepository.findById(31L)).thenReturn(Optional.of(workerA));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, OCTOBER)).thenReturn(Optional.of(locked));
        when(workerPayrollRepository.findByPayrollPeriodIdAndWorkerId(locked.getId(), 31L)).thenReturn(Optional.of(payroll));

        WarehousePayrollStatementResponse statement = service.getWorkerStatement(10L, 31L, "2026-10");

        assertTrue(statement.calculated());
        assertEquals(PayrollPeriodStatus.LOCKED, statement.status());
        assertEquals(new BigDecimal("150.50"), statement.rateVndPerKg());
        assertEquals(new BigDecimal("20.000"), statement.kpiTargetKg());
        assertEquals(new BigDecimal("5303424"), statement.totalSalaryVnd());
        assertEquals(List.of("TRIP-0071", "TRIP-0072"), statement.trips().stream().map(t -> t.tripCode()).toList());
    }

    @Test
    void statementOfSomeoneWhoIsNotACompanyWarehouseWorkerIsNotFound() {
        User driver = User.builder().id(40L).company(company).userRoleType(UserRole.DRIVER).status(UserStatus.ACTIVE).build();
        User otherCompanyWorker = User.builder().id(41L).company(Company.builder().id(11L).build())
                .userRoleType(UserRole.WAREHOUSE_WORKER).status(UserStatus.ACTIVE).build();
        when(userRepository.findById(40L)).thenReturn(Optional.of(driver));
        when(userRepository.findById(41L)).thenReturn(Optional.of(otherCompanyWorker));

        assertEquals(ErrorCode.USER_NOT_FOUND, assertThrows(AppException.class,
                () -> service.getWorkerStatement(10L, 40L, "2026-10")).getErrorCode());
        assertEquals(ErrorCode.USER_NOT_FOUND, assertThrows(AppException.class,
                () -> service.getCompanyWorkerStatement(10L, 41L, "2026-10")).getErrorCode());
    }

    @Test
    void lockingSetsLockedStatusAndTime() {
        WarehousePayrollPeriod draft = period(PayrollPeriodStatus.DRAFT);
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, OCTOBER)).thenReturn(Optional.of(draft));

        var response = service.lockPeriod(10L, "2026-10");

        assertEquals(PayrollPeriodStatus.LOCKED, response.status());
        assertEquals(PayrollPeriodStatus.LOCKED, draft.getStatus());
        assertTrue(draft.getLockedAt() != null);
    }

    @Test
    void cannotLockFuturePayrollPeriod() {
        String futurePeriod = YearMonth.now(VN).plusMonths(1).toString();

        AppException error = assertThrows(AppException.class, () -> service.lockPeriod(10L, futurePeriod));

        assertEquals("PAYROLL_FUTURE_PERIOD_CANNOT_LOCK", error.getErrorCode().name());
        verify(periodRepository, never()).findByCompanyIdAndPeriodStartForUpdate(10L,
                YearMonth.parse(futurePeriod).atDay(1));
        verify(periodRepository, never()).save(any(WarehousePayrollPeriod.class));
    }

    @Test
    void deletingLockedPeriodIsRejected() {
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, OCTOBER))
                .thenReturn(Optional.of(period(PayrollPeriodStatus.LOCKED)));

        assertEquals(ErrorCode.PAYROLL_PERIOD_LOCKED, assertThrows(AppException.class,
                () -> service.deleteDraftPeriod(10L, "2026-10")).getErrorCode());
        verify(periodRepository, never()).delete(any(WarehousePayrollPeriod.class));
    }

    @Test
    void baseSalaryDefaultsToNextMonthAndEmailsWorkerOnRaise() {
        LocalDate nextMonth = YearMonth.now(VN).plusMonths(1).atDay(1);
        when(userRepository.findById(31L)).thenReturn(Optional.of(workerA));
        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, nextMonth))
                .thenReturn(Optional.of(rate));
        when(baseSalaryOverrideRepository.save(any(WarehouseBaseSalaryOverride.class))).thenAnswer(call -> {
            WarehouseBaseSalaryOverride saved = call.getArgument(0);
            saved.setId(800L);
            return saved;
        });

        var response = service.setWorkerBaseSalary(10L, 31L, new WarehouseBaseSalaryRequest(null, new BigDecimal("6000000")));

        assertEquals(nextMonth, response.effectiveFrom());
        assertEquals(new BigDecimal("6000000"), response.baseSalaryVnd());
        assertEquals(31L, response.workerId());
        verify(brevoClient).sendEmail(eq("kho31@example.test"), anyString(), anyString());
    }

    @Test
    void baseSalaryBelowCompanyBaseSendsNoEmail() {
        LocalDate nextMonth = YearMonth.now(VN).plusMonths(1).atDay(1);
        when(userRepository.findById(31L)).thenReturn(Optional.of(workerA));
        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, nextMonth))
                .thenReturn(Optional.of(rate));
        when(baseSalaryOverrideRepository.save(any(WarehouseBaseSalaryOverride.class))).thenAnswer(call -> call.getArgument(0));

        service.setWorkerBaseSalary(10L, 31L, new WarehouseBaseSalaryRequest(nextMonth, new BigDecimal("4000000")));

        verify(brevoClient, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void baseSalaryCannotStartThisMonthOrMidMonth() {
        LocalDate thisMonth = YearMonth.now(VN).atDay(1);
        LocalDate nextMonth = YearMonth.now(VN).plusMonths(1).atDay(1);

        assertEquals(ErrorCode.PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY, assertThrows(AppException.class,
                () -> service.setWorkerBaseSalary(10L, 31L, new WarehouseBaseSalaryRequest(thisMonth, BigDecimal.TEN)))
                .getErrorCode());
        assertEquals(ErrorCode.PAYROLL_INVALID_PERIOD, assertThrows(AppException.class,
                () -> service.setWorkerBaseSalary(10L, 31L, new WarehouseBaseSalaryRequest(nextMonth.plusDays(4), BigDecimal.TEN)))
                .getErrorCode());
    }

    @Test
    void baseSalaryOnlyForCompanyWarehouseWorkers() {
        User driver = User.builder().id(40L).company(company).userRoleType(UserRole.DRIVER).status(UserStatus.ACTIVE).build();
        when(userRepository.findById(40L)).thenReturn(Optional.of(driver));

        assertEquals(ErrorCode.USER_NOT_FOUND, assertThrows(AppException.class,
                () -> service.setWorkerBaseSalary(10L, 40L, new WarehouseBaseSalaryRequest(null, BigDecimal.TEN)))
                .getErrorCode());
    }

    @Test
    void kpiTargetRequiresAPositiveBonus() {
        LocalDate nextMonth = YearMonth.now(VN).plusMonths(1).atDay(1);
        var request = new CompanyWarehousePayrollRateRequest(nextMonth, new BigDecimal("5000000"), new BigDecimal("150"),
                new BigDecimal("1000"), new BigDecimal("0.40"));

        assertEquals(ErrorCode.PAYROLL_KPI_BONUS_REQUIRED,
                assertThrows(AppException.class, () -> service.createRate(10L, request)).getErrorCode());
    }

    @Test
    void firstRateMayStartThisMonthButNotEarlier() {
        LocalDate thisMonth = YearMonth.now(VN).atDay(1);
        when(rateRepository.existsByCompanyId(10L)).thenReturn(false);
        when(rateRepository.findByCompanyIdAndEffectiveFrom(eq(10L), any())).thenReturn(Optional.empty());
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, thisMonth)).thenReturn(Optional.empty());
        when(rateRepository.save(any(CompanyWarehousePayrollRate.class))).thenAnswer(call -> call.getArgument(0));

        var created = service.createRate(10L, new CompanyWarehousePayrollRateRequest(
                thisMonth, new BigDecimal("5000000"), new BigDecimal("150")));

        assertEquals(thisMonth, created.effectiveFrom());
        assertNull(created.kpiTargetKg());
        assertEquals(0, created.kpiBonusVnd().signum());
        assertEquals(ErrorCode.PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY, assertThrows(AppException.class,
                () -> service.createRate(10L, new CompanyWarehousePayrollRateRequest(
                        thisMonth.minusMonths(1), new BigDecimal("5000000"), new BigDecimal("150")))).getErrorCode());
    }

    @Test
    void editingRateUsedByALockedPeriodIsRejected() {
        when(rateRepository.existsByCompanyId(10L)).thenReturn(true);
        when(rateRepository.findByCompanyIdAndEffectiveFrom(10L, OCTOBER)).thenReturn(Optional.of(rate));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, OCTOBER)).thenReturn(Optional.empty());
        when(periodRepository.existsByCompanyIdAndPayrollRateIdAndStatus(10L, 5L, PayrollPeriodStatus.LOCKED)).thenReturn(true);

        AppException error = assertThrows(AppException.class, () -> service.createRate(10L,
                new CompanyWarehousePayrollRateRequest(OCTOBER, new BigDecimal("5500000"), new BigDecimal("160"))));

        assertEquals(ErrorCode.PAYROLL_PERIOD_LOCKED, error.getErrorCode());
        verify(rateRepository, never()).save(any(CompanyWarehousePayrollRate.class));
    }

    private void givenOctoberRateAndNewPeriod() {
        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, OCTOBER))
                .thenReturn(Optional.of(rate));
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, OCTOBER)).thenReturn(Optional.empty());
    }

    private WarehousePayrollPeriod savedPeriod() {
        ArgumentCaptor<WarehousePayrollPeriod> captor = ArgumentCaptor.forClass(WarehousePayrollPeriod.class);
        verify(periodRepository).save(captor.capture());
        return captor.getValue();
    }

    private WarehousePayrollPeriod period(PayrollPeriodStatus status) {
        return WarehousePayrollPeriod.builder()
                .id(300L).company(company).payrollRate(rate).periodStart(OCTOBER).status(status)
                .workerPayrolls(new ArrayList<>())
                .build();
    }

    private User worker(Long id, String name, UserStatus status) {
        return User.builder().id(id).company(company).fullName(name).email("kho" + id + "@example.test")
                .userRoleType(UserRole.WAREHOUSE_WORKER).status(status).build();
    }

    private WarehouseBaseSalaryOverride override(User worker, String amount) {
        return WarehouseBaseSalaryOverride.builder().id(worker.getId() + 1000).company(company).worker(worker)
                .effectiveFrom(OCTOBER.minusMonths(1)).baseSalaryVnd(new BigDecimal(amount)).build();
    }

    private static CargoPackage actual(String kg) {
        return CargoPackage.builder().actualWeightKg(new BigDecimal(kg)).build();
    }

    private static CargoPackage typed(String kg) {
        return CargoPackage.builder().packageType(PackageType.builder().weightKg(new BigDecimal(kg)).build()).build();
    }

    private static PlacementConfirmation loading(User worker, Trip trip, CargoPackage cargo, LocalDateTime at) {
        OptimizationJob job = OptimizationJob.builder().trip(trip).build();
        LoadPlan plan = LoadPlan.builder().job(job).build();
        PackagePlacement placement = PackagePlacement.builder().loadPlan(plan).cargoPackage(cargo).build();
        return PlacementConfirmation.builder().placement(placement).confirmedBy(worker).confirmedAt(at).build();
    }
}
