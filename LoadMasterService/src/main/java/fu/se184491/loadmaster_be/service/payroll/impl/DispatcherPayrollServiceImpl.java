package fu.se184491.loadmaster_be.service.payroll.impl;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.dto.request.payroll.CompanyDispatcherPayrollRateRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.DispatcherBaseSalaryRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.DispatcherPayrollLineRequest;
import fu.se184491.loadmaster_be.dto.response.payroll.CompanyDispatcherPayrollRateResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherBaseSalaryResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherPayrollPeriodResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherPayrollStatementResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherPayrollSummaryResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.payroll.CompanyDispatcherPayrollRate;
import fu.se184491.loadmaster_be.entity.payroll.DispatcherBaseSalaryOverride;
import fu.se184491.loadmaster_be.entity.payroll.DispatcherPayroll;
import fu.se184491.loadmaster_be.entity.payroll.DispatcherPayrollPeriod;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.AfterCommit;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.payroll.CompanyDispatcherPayrollRateRepository;
import fu.se184491.loadmaster_be.repository.payroll.DispatcherBaseSalaryOverrideRepository;
import fu.se184491.loadmaster_be.repository.payroll.DispatcherPayrollPeriodRepository;
import fu.se184491.loadmaster_be.repository.payroll.DispatcherPayrollRepository;
import fu.se184491.loadmaster_be.service.payroll.DispatcherPayrollService;
import fu.se184491.loadmaster_be.service.payroll.PayrollMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class DispatcherPayrollServiceImpl implements DispatcherPayrollService {

    private static final ZoneId VIETNAM_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final CompanyDispatcherPayrollRateRepository rateRepository;
    private final CompanyRepository companyRepository;
    private final DispatcherBaseSalaryOverrideRepository baseSalaryOverrideRepository;
    private final DispatcherPayrollPeriodRepository periodRepository;
    private final DispatcherPayrollRepository dispatcherPayrollRepository;
    private final UserRepository userRepository;
    private final BrevoClient brevoClient;

    @Override
    @Transactional(readOnly = true)
    public List<CompanyDispatcherPayrollRateResponse> getRates(Long companyId) {
        return rateRepository.findByCompanyIdOrderByEffectiveFromDesc(companyId).stream()
                .map(this::rateResponse)
                .toList();
    }

    @Override
    public CompanyDispatcherPayrollRateResponse createRate(Long companyId, CompanyDispatcherPayrollRateRequest request) {
        lockCompanyForPayrollUpdate(companyId);
        LocalDate effectiveFrom = request.effectiveFrom();
        if (effectiveFrom.getDayOfMonth() != 1) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_PERIOD);
        }
        Optional<CompanyDispatcherPayrollRate> existingRate = rateRepository.findByCompanyIdAndEffectiveFrom(companyId, effectiveFrom);
        boolean hasExistingRate = rateRepository.existsByCompanyId(companyId);
        LocalDate earliestAllowed = hasExistingRate
                ? YearMonth.now(VIETNAM_TIME_ZONE).plusMonths(1).atDay(1)
                : YearMonth.now(VIETNAM_TIME_ZONE).atDay(1);
        if (existingRate.isEmpty() && effectiveFrom.isBefore(earliestAllowed)) {
            throw new AppException(ErrorCode.PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY);
        }

        periodRepository.findByCompanyIdAndPeriodStart(companyId, effectiveFrom)
                .filter(period -> period.getStatus() == PayrollPeriodStatus.LOCKED)
                .ifPresent(period -> { throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED); });
        if (existingRate.isPresent() && periodRepository.existsByCompanyIdAndPayrollRateIdAndStatus(
                companyId, existingRate.get().getId(), PayrollPeriodStatus.LOCKED)) {
            throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED);
        }
        List<DispatcherPayrollPeriod> affectedDrafts = existingRate.isPresent()
                ? periodRepository.findByCompanyIdAndPayrollRateIdAndStatusOrderByPeriodStartAsc(
                        companyId, existingRate.get().getId(), PayrollPeriodStatus.DRAFT)
                : List.of();

        CompanyDispatcherPayrollRate rate = existingRate
                .orElseGet(() -> CompanyDispatcherPayrollRate.builder()
                        .company(companyReference(companyId))
                        .effectiveFrom(effectiveFrom)
                        .build());
        rate.setBaseSalaryVnd(request.baseSalaryVnd());
        rate.setOvertimeVndPerHour(request.overtimeVndPerHour());
        rate.setOvertimeHoursPerDay(request.overtimeHoursPerDay());

        CompanyDispatcherPayrollRate savedRate = rateRepository.save(rate);
        for (DispatcherPayrollPeriod draft : affectedDrafts) {
            calculatePeriod(companyId, YearMonth.from(draft.getPeriodStart()).toString());
        }
        return rateResponse(savedRate);
    }

    @Override
    public DispatcherBaseSalaryResponse setDispatcherBaseSalary(
            Long companyId, Long dispatcherId, DispatcherBaseSalaryRequest request) {
        if (request.effectiveFrom().getDayOfMonth() != 1) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_PERIOD);
        }
        if (request.effectiveFrom().isBefore(YearMonth.now(VIETNAM_TIME_ZONE).plusMonths(1).atDay(1))) {
            throw new AppException(ErrorCode.PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY);
        }
        User dispatcher = userRepository.findById(dispatcherId)
                .filter(user -> isCompanyDispatcher(user, companyId))
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Optional<DispatcherBaseSalaryOverride> current = baseSalaryOverrideRepository
                .findFirstByDispatcherIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        dispatcherId, request.effectiveFrom());
        BigDecimal companyBase = rateRepository
                .findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(companyId, request.effectiveFrom())
                .map(CompanyDispatcherPayrollRate::getBaseSalaryVnd)
                .orElse(BigDecimal.ZERO);
        BigDecimal before = current.map(DispatcherBaseSalaryOverride::getBaseSalaryVnd)
                .map(raise -> raise.max(companyBase)).orElse(companyBase).setScale(0, RoundingMode.HALF_UP);

        DispatcherBaseSalaryOverride override = current
                .filter(existing -> existing.getEffectiveFrom().equals(request.effectiveFrom()))
                .orElseGet(() -> DispatcherBaseSalaryOverride.builder()
                        .company(companyReference(companyId))
                        .dispatcher(dispatcher)
                        .effectiveFrom(request.effectiveFrom())
                        .build());
        override.setBaseSalaryVnd(request.baseSalaryVnd());
        override = baseSalaryOverrideRepository.save(override);

        BigDecimal after = override.getBaseSalaryVnd().max(companyBase).setScale(0, RoundingMode.HALF_UP);
        if (after.compareTo(before) > 0) {
            notifyBaseSalaryRaised(dispatcher, request.effectiveFrom(), before, after);
        }
        return new DispatcherBaseSalaryResponse(override.getId(), dispatcher.getId(),
                dispatcher.getFullName(), override.getEffectiveFrom(), override.getBaseSalaryVnd());
    }

    @Override
    public DispatcherPayrollPeriodResponse calculatePeriod(Long companyId, String period) {
        lockCompanyForPayrollUpdate(companyId);
        YearMonth month = parsePeriod(period);
        LocalDate periodStart = month.atDay(1);
        CompanyDispatcherPayrollRate rate = rateRepository
                .findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(companyId, periodStart)
                .orElseThrow(() -> new AppException(ErrorCode.PAYROLL_RATE_NOT_CONFIGURED));

        DispatcherPayrollPeriod payrollPeriod = periodRepository.findByCompanyIdAndPeriodStartForUpdate(companyId, periodStart)
                .orElseGet(() -> DispatcherPayrollPeriod.builder()
                        .company(companyReference(companyId))
                        .periodStart(periodStart)
                        .status(PayrollPeriodStatus.DRAFT)
                        .build());
        if (payrollPeriod.getStatus() == PayrollPeriodStatus.LOCKED) {
            throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED);
        }
        payrollPeriod.setPayrollRate(rate);

        Map<Long, LineInputs> preserved = new HashMap<>();
        if (payrollPeriod.getId() != null) {
            for (DispatcherPayroll line : payrollPeriod.getDispatcherPayrolls()) {
                preserved.put(line.getDispatcher().getId(),
                        new LineInputs(line.getOvertimeDays(), line.getKpiAmountVnd()));
            }
            payrollPeriod.getDispatcherPayrolls().clear();
            periodRepository.flush();
        }

        List<User> dispatchers = userRepository
                .findByCompanyIdAndUserRoleTypeAndStatus(companyId, UserRole.DISPATCHER, UserStatus.ACTIVE)
                .stream()
                .sorted(Comparator.comparing(User::getId))
                .toList();
        for (User dispatcher : dispatchers) {
            LineInputs inputs = preserved.getOrDefault(dispatcher.getId(), LineInputs.ZERO);
            payrollPeriod.getDispatcherPayrolls().add(newLine(payrollPeriod, dispatcher, rate, inputs));
        }
        periodRepository.save(payrollPeriod);
        return periodResponse(payrollPeriod);
    }

    @Override
    @Transactional(readOnly = true)
    public DispatcherPayrollPeriodResponse getPeriod(Long companyId, String period) {
        return periodResponse(findPeriod(companyId, parsePeriod(period).atDay(1)));
    }

    @Override
    public void deleteDraftPeriod(Long companyId, String period) {
        DispatcherPayrollPeriod payrollPeriod = findPeriodForUpdate(companyId, parsePeriod(period).atDay(1));
        if (payrollPeriod.getStatus() == PayrollPeriodStatus.LOCKED) {
            throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED);
        }
        periodRepository.delete(payrollPeriod);
    }

    @Override
    public DispatcherPayrollPeriodResponse lockPeriod(Long companyId, String period) {
        DispatcherPayrollPeriod payrollPeriod = findPeriod(companyId, parsePeriod(period).atDay(1));
        if (payrollPeriod.getStatus() != PayrollPeriodStatus.LOCKED) {
            payrollPeriod.setStatus(PayrollPeriodStatus.LOCKED);
            payrollPeriod.setLockedAt(LocalDateTime.now(VIETNAM_TIME_ZONE).truncatedTo(ChronoUnit.SECONDS));
            periodRepository.save(payrollPeriod);
        }
        return periodResponse(payrollPeriod);
    }

    @Override
    public DispatcherPayrollPeriodResponse updateLine(
            Long companyId, String period, Long dispatcherId, DispatcherPayrollLineRequest request) {
        lockCompanyForPayrollUpdate(companyId);
        YearMonth month = parsePeriod(period);
        DispatcherPayrollPeriod payrollPeriod = findPeriodForUpdate(companyId, month.atDay(1));
        if (payrollPeriod.getStatus() == PayrollPeriodStatus.LOCKED) {
            throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED);
        }
        requireOvertimeDays(month, request.overtimeDays());
        DispatcherPayroll line = dispatcherPayrollRepository
                .findByPayrollPeriodIdAndDispatcherId(payrollPeriod.getId(), dispatcherId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        applyAmounts(line, request.overtimeDays(), request.kpiAmountVnd());
        return periodResponse(payrollPeriod);
    }

    @Override
    @Transactional(readOnly = true)
    public DispatcherPayrollStatementResponse getCompanyDispatcherStatement(Long companyId, Long dispatcherId, String period) {
        User dispatcher = requireCompanyDispatcher(companyId, dispatcherId);
        return statement(companyId, dispatcher, parsePeriod(period));
    }

    @Override
    @Transactional(readOnly = true)
    public DispatcherPayrollStatementResponse getDispatcherStatement(Long companyId, Long dispatcherId, String period) {
        User dispatcher = requireCompanyDispatcher(companyId, dispatcherId);
        return statement(companyId, dispatcher, parsePeriod(period));
    }

    private void notifyBaseSalaryRaised(User dispatcher, LocalDate effectiveFrom, BigDecimal before, BigDecimal after) {
        if (brevoClient == null || dispatcher.getEmail() == null || dispatcher.getEmail().isBlank()) return;
        String recipient = dispatcher.getEmail();
        String month = String.format("%02d/%d", effectiveFrom.getMonthValue(), effectiveFrom.getYear());
        NumberFormat money = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"));
        String name = HtmlUtils.htmlEscape(dispatcher.getFullName() != null ? dispatcher.getFullName() : recipient);
        String companyName = dispatcher.getCompany() != null && dispatcher.getCompany().getCompanyName() != null
                ? HtmlUtils.htmlEscape(dispatcher.getCompany().getCompanyName()) : "";
        String previous = before.signum() > 0 ? " (trước đây " + money.format(before) + " đ)" : "";
        String subject = "LoadMaster - Lương cứng của bạn được điều chỉnh từ tháng " + month;
        String content = "Xin chào <strong>" + name + "</strong>,\n\n"
                + (companyName.isEmpty() ? "Công ty" : "Công ty <strong>" + companyName + "</strong>")
                + " vừa điều chỉnh lương cứng của bạn.\n\n"
                + "Từ tháng " + month + ", lương cứng của bạn là <strong>" + money.format(after) + " đ</strong>" + previous + ".\n\n"
                + "Bạn có thể xem chi tiết trong mục Phiếu lương trên ứng dụng của công ty.\n\n"
                + "Trân trọng,\nĐội ngũ LoadMaster";
        AfterCommit.run("email tăng lương cứng điều phối", () -> {
            try {
                brevoClient.sendEmail(recipient, subject, content);
            } catch (RuntimeException ex) {
                log.warn("Không gửi được email tăng lương cứng cho điều phối {}: {}", dispatcher.getId(), ex.getMessage());
            }
        });
    }

    private DispatcherPayroll newLine(
            DispatcherPayrollPeriod period,
            User dispatcher,
            CompanyDispatcherPayrollRate rate,
            LineInputs inputs
    ) {
        DispatcherPayroll line = DispatcherPayroll.builder()
                .payrollPeriod(period)
                .dispatcher(dispatcher)
                .overtimeVndPerHour(rate.getOvertimeVndPerHour())
                .overtimeHoursPerDay(rate.getOvertimeHoursPerDay())
                .build();
        line.setBaseSalaryVnd(dispatcherBaseSalary(dispatcher.getId(), period.getPeriodStart(), rate));
        applyAmounts(line, inputs.overtimeDays(), inputs.kpiAmountVnd());
        return line;
    }

    private void applyAmounts(DispatcherPayroll line, int overtimeDays, BigDecimal kpiAmountVnd) {
        BigDecimal overtimeAmount = PayrollMath.overtimeAmount(
                line.getOvertimeVndPerHour(), line.getOvertimeHoursPerDay(), overtimeDays);
        BigDecimal kpi = kpiAmountVnd == null ? BigDecimal.ZERO : kpiAmountVnd.setScale(0, RoundingMode.HALF_UP);
        if (kpi.signum() < 0) throw new AppException(ErrorCode.PAYROLL_INVALID_AMOUNT);
        line.setOvertimeDays(overtimeDays);
        line.setOvertimeAmountVnd(overtimeAmount);
        line.setKpiAmountVnd(kpi);
        line.setTotalSalaryVnd(line.getBaseSalaryVnd().add(overtimeAmount).add(kpi));
    }

    private BigDecimal dispatcherBaseSalary(Long dispatcherId, LocalDate periodStart, CompanyDispatcherPayrollRate rate) {
        BigDecimal companyBase = rate.getBaseSalaryVnd();
        BigDecimal base = baseSalaryOverrideRepository
                .findFirstByDispatcherIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(dispatcherId, periodStart)
                .map(DispatcherBaseSalaryOverride::getBaseSalaryVnd)
                .map(raise -> raise.max(companyBase))
                .orElse(companyBase);
        return base.setScale(0, RoundingMode.HALF_UP);
    }

    private DispatcherPayrollStatementResponse statement(Long companyId, User dispatcher, YearMonth month) {
        LocalDate periodStart = month.atDay(1);
        DispatcherPayrollPeriod payrollPeriod = periodRepository.findByCompanyIdAndPeriodStart(companyId, periodStart)
                .orElse(null);
        if (payrollPeriod == null) {
            return uncalculated(month, null, dispatcher);
        }
        DispatcherPayroll line = dispatcherPayrollRepository
                .findByPayrollPeriodIdAndDispatcherId(payrollPeriod.getId(), dispatcher.getId())
                .orElse(null);
        if (line == null) {
            return uncalculated(month, payrollPeriod.getStatus(), dispatcher);
        }
        return new DispatcherPayrollStatementResponse(
                true, month.toString(), payrollPeriod.getStatus(), dispatcher.getId(), dispatcher.getFullName(),
                line.getBaseSalaryVnd(), line.getOvertimeVndPerHour(), line.getOvertimeHoursPerDay(),
                line.getOvertimeDays(), line.getOvertimeAmountVnd(), line.getKpiAmountVnd(), line.getTotalSalaryVnd());
    }

    private DispatcherPayrollStatementResponse uncalculated(YearMonth month, PayrollPeriodStatus status, User dispatcher) {
        return new DispatcherPayrollStatementResponse(
                false, month.toString(), status, dispatcher.getId(), dispatcher.getFullName(),
                null, null, null, null, null, null, null);
    }

    private DispatcherPayrollPeriodResponse periodResponse(DispatcherPayrollPeriod period) {
        List<DispatcherPayrollSummaryResponse> dispatchers = period.getDispatcherPayrolls().stream()
                .sorted(Comparator.comparing(line -> line.getDispatcher().getId()))
                .map(line -> new DispatcherPayrollSummaryResponse(
                        line.getDispatcher().getId(),
                        line.getDispatcher().getFullName(),
                        line.getBaseSalaryVnd(),
                        line.getOvertimeDays(),
                        line.getOvertimeAmountVnd(),
                        line.getKpiAmountVnd(),
                        line.getTotalSalaryVnd()))
                .toList();
        return new DispatcherPayrollPeriodResponse(
                YearMonth.from(period.getPeriodStart()).toString(), period.getStatus(), dispatchers);
    }

    private CompanyDispatcherPayrollRateResponse rateResponse(CompanyDispatcherPayrollRate rate) {
        return new CompanyDispatcherPayrollRateResponse(
                rate.getId(), rate.getEffectiveFrom(), rate.getBaseSalaryVnd(),
                rate.getOvertimeVndPerHour(), rate.getOvertimeHoursPerDay());
    }

    private DispatcherPayrollPeriod findPeriod(Long companyId, LocalDate periodStart) {
        return periodRepository.findByCompanyIdAndPeriodStart(companyId, periodStart)
                .orElseThrow(() -> new AppException(ErrorCode.PAYROLL_PERIOD_NOT_FOUND));
    }

    private DispatcherPayrollPeriod findPeriodForUpdate(Long companyId, LocalDate periodStart) {
        return periodRepository.findByCompanyIdAndPeriodStartForUpdate(companyId, periodStart)
                .orElseThrow(() -> new AppException(ErrorCode.PAYROLL_PERIOD_NOT_FOUND));
    }

    private YearMonth parsePeriod(String period) {
        try {
            YearMonth month = YearMonth.parse(period);
            if (!month.toString().equals(period)) throw new DateTimeParseException("Invalid payroll period", period, 0);
            return month;
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_PERIOD);
        }
    }

    private void requireOvertimeDays(YearMonth month, int overtimeDays) {
        if (overtimeDays < 0 || overtimeDays > month.lengthOfMonth()) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_OVERTIME_DAYS);
        }
    }

    private User requireCompanyDispatcher(Long companyId, Long dispatcherId) {
        return userRepository.findById(dispatcherId)
                .filter(user -> isCompanyDispatcher(user, companyId))
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    private boolean isCompanyDispatcher(User user, Long companyId) {
        return user != null
                && user.getCompany() != null
                && Objects.equals(user.getCompany().getId(), companyId)
                && user.getUserRoleType() == UserRole.DISPATCHER;
    }

    private fu.se184491.loadmaster_be.entity.company.Company companyReference(Long companyId) {
        return fu.se184491.loadmaster_be.entity.company.Company.builder().id(companyId).build();
    }

    private void lockCompanyForPayrollUpdate(Long companyId) {
        companyRepository.findByIdForPayrollUpdate(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
    }

    private record LineInputs(int overtimeDays, BigDecimal kpiAmountVnd) {
        static final LineInputs ZERO = new LineInputs(0, BigDecimal.ZERO);
    }
}
