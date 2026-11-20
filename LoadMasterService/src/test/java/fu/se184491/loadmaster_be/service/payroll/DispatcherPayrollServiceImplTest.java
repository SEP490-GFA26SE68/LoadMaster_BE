package fu.se184491.loadmaster_be.service.payroll;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.dto.request.payroll.CompanyDispatcherPayrollRateRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.DispatcherBaseSalaryRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.DispatcherPayrollLineRequest;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherPayrollPeriodResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.payroll.CompanyDispatcherPayrollRate;
import fu.se184491.loadmaster_be.entity.payroll.DispatcherBaseSalaryOverride;
import fu.se184491.loadmaster_be.entity.payroll.DispatcherPayroll;
import fu.se184491.loadmaster_be.entity.payroll.DispatcherPayrollPeriod;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.payroll.CompanyDispatcherPayrollRateRepository;
import fu.se184491.loadmaster_be.repository.payroll.DispatcherBaseSalaryOverrideRepository;
import fu.se184491.loadmaster_be.repository.payroll.DispatcherPayrollPeriodRepository;
import fu.se184491.loadmaster_be.repository.payroll.DispatcherPayrollRepository;
import fu.se184491.loadmaster_be.service.payroll.impl.DispatcherPayrollServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispatcherPayrollServiceImplTest {

    @Mock private CompanyDispatcherPayrollRateRepository rateRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private DispatcherBaseSalaryOverrideRepository baseSalaryOverrideRepository;
    @Mock private DispatcherPayrollPeriodRepository periodRepository;
    @Mock private DispatcherPayrollRepository dispatcherPayrollRepository;
    @Mock private UserRepository userRepository;
    @Mock private BrevoClient brevoClient;

    @InjectMocks private DispatcherPayrollServiceImpl service;

    private Company company;
    private User dispatcher;
    private CompanyDispatcherPayrollRate rate;

    @BeforeEach
    void setUp() {
        company = Company.builder().id(10L).companyName("Công ty TNHH Vận tải Long Bình").build();
        lenient().when(companyRepository.findByIdForPayrollUpdate(10L)).thenReturn(Optional.of(company));
        dispatcher = dispatcher(31L, "Nguyễn Thanh Tùng", company);
        rate = CompanyDispatcherPayrollRate.builder()
                .id(4L)
                .company(company)
                .effectiveFrom(LocalDate.of(2026, 11, 1))
                .baseSalaryVnd(new BigDecimal("10000000"))
                .overtimeVndPerHour(new BigDecimal("50000"))
                .overtimeHoursPerDay(new BigDecimal("2.00"))
                .build();
    }

    @Test
    void allowsCurrentMonthRateForNewCompanyWithoutPriorRates() {
        LocalDate currentMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).atDay(1);
        when(rateRepository.existsByCompanyId(10L)).thenReturn(false);
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, currentMonth)).thenReturn(Optional.empty());
        when(rateRepository.findByCompanyIdAndEffectiveFrom(10L, currentMonth)).thenReturn(Optional.empty());
        when(rateRepository.save(any(CompanyDispatcherPayrollRate.class))).thenAnswer(invocation -> {
            CompanyDispatcherPayrollRate saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        var response = service.createRate(10L, new CompanyDispatcherPayrollRateRequest(
                currentMonth, new BigDecimal("10000000"), new BigDecimal("50000"), new BigDecimal("2.00")));

        assertNotNull(response);
        assertEquals(new BigDecimal("50000"), response.overtimeVndPerHour());
        assertEquals(new BigDecimal("2.00"), response.overtimeHoursPerDay());
    }

    @Test
    void rejectsNewRateEffectiveInCurrentMonthWhenRatesAlreadyExist() {
        LocalDate currentMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).atDay(1);
        when(rateRepository.existsByCompanyId(10L)).thenReturn(true);
        when(rateRepository.findByCompanyIdAndEffectiveFrom(10L, currentMonth)).thenReturn(Optional.empty());

        AppException error = assertThrows(AppException.class, () -> service.createRate(10L,
                new CompanyDispatcherPayrollRateRequest(
                        currentMonth, new BigDecimal("10000000"), new BigDecimal("50000"), new BigDecimal("2.00"))));

        assertEquals("PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY", error.getErrorCode().name());
    }

    @Test
    void calculatePeriodUsesBasePlusOvertimePlusKpi() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        stubCalculate(periodStart, Optional.empty());
        when(periodRepository.save(any(DispatcherPayrollPeriod.class))).thenAnswer(invocation -> {
            DispatcherPayrollPeriod period = invocation.getArgument(0);
            period.setId(8L);
            return period;
        });

        DispatcherPayrollPeriodResponse response = service.calculatePeriod(10L, "2026-11");

        assertEquals("2026-11", response.period());
        assertEquals(PayrollPeriodStatus.DRAFT, response.status());
        assertEquals(1, response.dispatchers().size());
        assertEquals(new BigDecimal("10000000"), response.dispatchers().getFirst().baseSalaryVnd());
        assertEquals(0, response.dispatchers().getFirst().overtimeDays());
        assertEquals(new BigDecimal("0"), response.dispatchers().getFirst().overtimeAmountVnd());
        assertEquals(new BigDecimal("0"), response.dispatchers().getFirst().kpiAmountVnd());
        assertEquals(new BigDecimal("10000000"), response.dispatchers().getFirst().totalSalaryVnd());
    }

    @Test
    void calculatePeriodKeepsEnteredOvertimeDaysAndKpi() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        DispatcherPayrollPeriod existing = DispatcherPayrollPeriod.builder()
                .id(8L)
                .company(company)
                .periodStart(periodStart)
                .payrollRate(rate)
                .status(PayrollPeriodStatus.DRAFT)
                .dispatcherPayrolls(new ArrayList<>())
                .build();
        DispatcherPayroll previous = DispatcherPayroll.builder()
                .payrollPeriod(existing)
                .dispatcher(dispatcher)
                .baseSalaryVnd(new BigDecimal("10000000"))
                .overtimeVndPerHour(new BigDecimal("40000"))
                .overtimeHoursPerDay(new BigDecimal("1.00"))
                .overtimeDays(3)
                .overtimeAmountVnd(new BigDecimal("120000"))
                .kpiAmountVnd(new BigDecimal("1000000"))
                .totalSalaryVnd(new BigDecimal("11120000"))
                .build();
        existing.getDispatcherPayrolls().add(previous);
        stubCalculate(periodStart, Optional.of(existing));
        when(periodRepository.save(any(DispatcherPayrollPeriod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DispatcherPayrollPeriodResponse response = service.calculatePeriod(10L, "2026-11");

        assertEquals(3, response.dispatchers().getFirst().overtimeDays());
        assertEquals(new BigDecimal("300000"), response.dispatchers().getFirst().overtimeAmountVnd());
        assertEquals(new BigDecimal("1000000"), response.dispatchers().getFirst().kpiAmountVnd());
        assertEquals(new BigDecimal("11300000"), response.dispatchers().getFirst().totalSalaryVnd());
    }

    @Test
    void calculatePeriodUsesDispatcherBaseSalaryOverride() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        stubCalculate(periodStart, Optional.empty());
        when(baseSalaryOverrideRepository
                .findFirstByDispatcherIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(31L, periodStart))
                .thenReturn(Optional.of(DispatcherBaseSalaryOverride.builder()
                        .company(company)
                        .dispatcher(dispatcher)
                        .effectiveFrom(periodStart)
                        .baseSalaryVnd(new BigDecimal("12000000"))
                        .build()));
        when(periodRepository.save(any(DispatcherPayrollPeriod.class))).thenAnswer(invocation -> {
            DispatcherPayrollPeriod period = invocation.getArgument(0);
            period.setId(8L);
            return period;
        });

        DispatcherPayrollPeriodResponse response = service.calculatePeriod(10L, "2026-11");

        assertEquals(new BigDecimal("12000000"), response.dispatchers().getFirst().baseSalaryVnd());
        assertEquals(new BigDecimal("12000000"), response.dispatchers().getFirst().totalSalaryVnd());
    }

    @Test
    void rejectsCalculateWhenPeriodIsLocked() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        DispatcherPayrollPeriod locked = DispatcherPayrollPeriod.builder()
                .id(8L)
                .status(PayrollPeriodStatus.LOCKED)
                .periodStart(periodStart)
                .payrollRate(rate)
                .dispatcherPayrolls(new ArrayList<>())
                .build();
        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, periodStart))
                .thenReturn(Optional.of(rate));
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, periodStart)).thenReturn(Optional.of(locked));

        AppException error = assertThrows(AppException.class, () -> service.calculatePeriod(10L, "2026-11"));
        assertEquals("PAYROLL_PERIOD_LOCKED", error.getErrorCode().name());
    }

    @Test
    void updateLineRecomputesOvertimeAndTotalWhileDraft() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        DispatcherPayrollPeriod draft = DispatcherPayrollPeriod.builder()
                .id(8L)
                .company(company)
                .periodStart(periodStart)
                .payrollRate(rate)
                .status(PayrollPeriodStatus.DRAFT)
                .dispatcherPayrolls(new ArrayList<>())
                .build();
        DispatcherPayroll line = DispatcherPayroll.builder()
                .payrollPeriod(draft)
                .dispatcher(dispatcher)
                .baseSalaryVnd(new BigDecimal("10000000"))
                .overtimeVndPerHour(new BigDecimal("50000"))
                .overtimeHoursPerDay(new BigDecimal("2.00"))
                .overtimeDays(0)
                .overtimeAmountVnd(BigDecimal.ZERO)
                .kpiAmountVnd(BigDecimal.ZERO)
                .totalSalaryVnd(new BigDecimal("10000000"))
                .build();
        draft.getDispatcherPayrolls().add(line);
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, periodStart)).thenReturn(Optional.of(draft));
        when(dispatcherPayrollRepository.findByPayrollPeriodIdAndDispatcherId(8L, 31L)).thenReturn(Optional.of(line));

        DispatcherPayrollPeriodResponse response = service.updateLine(
                10L, "2026-11", 31L, new DispatcherPayrollLineRequest(4, new BigDecimal("1500000")));

        assertEquals(4, response.dispatchers().getFirst().overtimeDays());
        assertEquals(new BigDecimal("400000"), response.dispatchers().getFirst().overtimeAmountVnd());
        assertEquals(new BigDecimal("1500000"), response.dispatchers().getFirst().kpiAmountVnd());
        assertEquals(new BigDecimal("11900000"), response.dispatchers().getFirst().totalSalaryVnd());
    }

    @Test
    void rejectsOvertimeDaysBeyondTheMonth() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        DispatcherPayrollPeriod draft = DispatcherPayrollPeriod.builder()
                .id(8L)
                .periodStart(periodStart)
                .status(PayrollPeriodStatus.DRAFT)
                .dispatcherPayrolls(new ArrayList<>())
                .build();
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, periodStart)).thenReturn(Optional.of(draft));

        AppException error = assertThrows(AppException.class, () -> service.updateLine(
                10L, "2026-11", 31L, new DispatcherPayrollLineRequest(31, BigDecimal.ZERO)));
        assertEquals("PAYROLL_INVALID_OVERTIME_DAYS", error.getErrorCode().name());
    }

    @Test
    void dispatcherSeesOwnDraftStatement() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        DispatcherPayrollPeriod draft = DispatcherPayrollPeriod.builder()
                .id(8L)
                .company(company)
                .periodStart(periodStart)
                .payrollRate(rate)
                .status(PayrollPeriodStatus.DRAFT)
                .build();
        DispatcherPayroll line = DispatcherPayroll.builder()
                .payrollPeriod(draft)
                .dispatcher(dispatcher)
                .baseSalaryVnd(new BigDecimal("10000000"))
                .overtimeVndPerHour(new BigDecimal("50000"))
                .overtimeHoursPerDay(new BigDecimal("2.00"))
                .overtimeDays(2)
                .overtimeAmountVnd(new BigDecimal("200000"))
                .kpiAmountVnd(new BigDecimal("500000"))
                .totalSalaryVnd(new BigDecimal("10700000"))
                .build();
        when(userRepository.findById(31L)).thenReturn(Optional.of(dispatcher));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, periodStart)).thenReturn(Optional.of(draft));
        when(dispatcherPayrollRepository.findByPayrollPeriodIdAndDispatcherId(8L, 31L)).thenReturn(Optional.of(line));

        var statement = service.getDispatcherStatement(10L, 31L, "2026-11");

        assertEquals(true, statement.calculated());
        assertEquals(PayrollPeriodStatus.DRAFT, statement.status());
        assertEquals(new BigDecimal("10700000"), statement.totalSalaryVnd());
        assertEquals(2, statement.overtimeDays());
    }

    @Test
    void dispatcherSeesUncalculatedWhenPeriodMissing() {
        when(userRepository.findById(31L)).thenReturn(Optional.of(dispatcher));
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, LocalDate.of(2026, 10, 1))).thenReturn(Optional.empty());

        var statement = service.getDispatcherStatement(10L, 31L, "2026-10");

        assertEquals(false, statement.calculated());
        assertEquals(null, statement.status());
    }

    @Test
    void rejectsDispatcherBaseSalaryEffectiveInCurrentMonth() {
        LocalDate currentMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).atDay(1);
        AppException error = assertThrows(AppException.class,
                () -> service.setDispatcherBaseSalary(10L, 31L, new DispatcherBaseSalaryRequest(currentMonth, new BigDecimal("9000000"))));
        assertEquals("PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY", error.getErrorCode().name());
    }

    @Test
    void savesNextMonthBaseSalaryForCompanyDispatcher() {
        LocalDate nextMonth = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusMonths(1).atDay(1);
        when(userRepository.findById(31L)).thenReturn(Optional.of(dispatcher));
        when(baseSalaryOverrideRepository
                .findFirstByDispatcherIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(31L, nextMonth))
                .thenReturn(Optional.empty());
        when(baseSalaryOverrideRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.setDispatcherBaseSalary(10L, 31L,
                new DispatcherBaseSalaryRequest(nextMonth, new BigDecimal("9000000")));

        assertEquals(31L, response.dispatcherId());
        assertEquals(new BigDecimal("9000000"), response.baseSalaryVnd());
    }

    @Test
    void lockPeriodSetsLockedStatus() {
        LocalDate periodStart = LocalDate.of(2026, 11, 1);
        DispatcherPayrollPeriod draft = DispatcherPayrollPeriod.builder()
                .id(8L)
                .company(company)
                .periodStart(periodStart)
                .status(PayrollPeriodStatus.DRAFT)
                .dispatcherPayrolls(new ArrayList<>())
                .build();
        when(periodRepository.findByCompanyIdAndPeriodStart(10L, periodStart)).thenReturn(Optional.of(draft));
        when(periodRepository.save(any(DispatcherPayrollPeriod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DispatcherPayrollPeriodResponse response = service.lockPeriod(10L, "2026-11");

        assertEquals(PayrollPeriodStatus.LOCKED, response.status());
        assertNotNull(draft.getLockedAt());
        verify(periodRepository).save(draft);
    }

    private void stubCalculate(LocalDate periodStart, Optional<DispatcherPayrollPeriod> existing) {
        when(rateRepository.findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(10L, periodStart))
                .thenReturn(Optional.of(rate));
        when(periodRepository.findByCompanyIdAndPeriodStartForUpdate(10L, periodStart)).thenReturn(existing);
        when(userRepository.findByCompanyIdAndUserRoleTypeAndStatus(10L, UserRole.DISPATCHER, UserStatus.ACTIVE))
                .thenReturn(List.of(dispatcher));
        lenient().when(baseSalaryOverrideRepository
                .findFirstByDispatcherIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(31L, periodStart))
                .thenReturn(Optional.empty());
    }

    private static User dispatcher(Long id, String name, Company company) {
        return User.builder()
                .id(id)
                .company(company)
                .fullName(name)
                .email(id + "@loadmaster.vn")
                .keycloakId("kc-" + id)
                .userRoleType(UserRole.DISPATCHER)
                .status(UserStatus.ACTIVE)
                .build();
    }
}
