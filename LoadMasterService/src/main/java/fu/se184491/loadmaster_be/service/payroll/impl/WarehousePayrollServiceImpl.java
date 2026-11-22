package fu.se184491.loadmaster_be.service.payroll.impl;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.dto.request.payroll.CompanyWarehousePayrollRateRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.WarehouseBaseSalaryRequest;
import fu.se184491.loadmaster_be.dto.response.payroll.CompanyWarehousePayrollRateResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehouseBaseSalaryResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollPeriodResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollStatementResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollSummaryResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollTripLineResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.payroll.CompanyWarehousePayrollRate;
import fu.se184491.loadmaster_be.entity.payroll.WarehouseBaseSalaryOverride;
import fu.se184491.loadmaster_be.entity.payroll.WarehousePayroll;
import fu.se184491.loadmaster_be.entity.payroll.WarehousePayrollPeriod;
import fu.se184491.loadmaster_be.entity.payroll.WarehousePayrollTripLine;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.warehouse.PlacementConfirmation;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.AfterCommit;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.payroll.CompanyWarehousePayrollRateRepository;
import fu.se184491.loadmaster_be.repository.payroll.WarehouseBaseSalaryOverrideRepository;
import fu.se184491.loadmaster_be.repository.payroll.WarehousePayrollPeriodRepository;
import fu.se184491.loadmaster_be.repository.payroll.WarehousePayrollRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.service.payroll.PayrollMath;
import fu.se184491.loadmaster_be.service.payroll.WarehousePayrollService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Lương nhân viên kho theo khuôn lương tài xế: đơn giá theo tháng áp dụng, lương cứng riêng chỉ nâng lên, kỳ nháp tính lại được, kỳ
 * chốt thì không. Kg của một người là tổng khối lượng các kiện có xác nhận xếp còn hiệu lực do người đó làm trong tháng (giờ Việt Nam).
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class WarehousePayrollServiceImpl implements WarehousePayrollService {

    private static final ZoneId VIETNAM_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final CompanyWarehousePayrollRateRepository rateRepository;
    private final CompanyRepository companyRepository;
    private final WarehouseBaseSalaryOverrideRepository baseSalaryOverrideRepository;
    private final WarehousePayrollPeriodRepository periodRepository;
    private final WarehousePayrollRepository workerPayrollRepository;
    private final PlacementConfirmationRepository placementConfirmationRepository;
    private final UserRepository userRepository;
    private final BrevoClient brevoClient;

    @Override
    @Transactional(readOnly = true)
    public List<CompanyWarehousePayrollRateResponse> getRates(Long companyId) {
        List<CompanyWarehousePayrollRate> rates = rateRepository.findByCompanyIdOrderByEffectiveFromDesc(companyId);
        if (rates.isEmpty()) return List.of();
        Map<LocalDate, PayrollPeriodStatus> statusByStart = periodRepository
                .findByCompanyIdAndPeriodStartIn(
                        companyId, rates.stream().map(CompanyWarehousePayrollRate::getEffectiveFrom).toList())
                .stream()
                .collect(Collectors.toMap(WarehousePayrollPeriod::getPeriodStart, WarehousePayrollPeriod::getStatus,
                        (first, ignored) -> first));
        return rates.stream()
                .map(rate -> rateResponse(rate, statusByStart.get(rate.getEffectiveFrom())))
                .toList();
    }

    @Override
    public CompanyWarehousePayrollRateResponse createRate(Long companyId, CompanyWarehousePayrollRateRequest request) {
        lockCompanyForPayrollUpdate(companyId);
        BigDecimal kpiTargetKg = request.kpiTargetKg();
        BigDecimal kpiBonusVnd = BigDecimal.ZERO;
        if (kpiTargetKg != null) {
            // Tiền thưởng trả theo đồng: số làm tròn về 0 đồng thì đạt KPI vẫn không được gì.
            if (request.kpiBonusVnd() == null || request.kpiBonusVnd().setScale(0, RoundingMode.HALF_UP).signum() <= 0) {
                throw new AppException(ErrorCode.PAYROLL_KPI_BONUS_REQUIRED);
            }
            kpiBonusVnd = request.kpiBonusVnd();
        }
        LocalDate effectiveFrom = request.effectiveFrom();
        if (effectiveFrom.getDayOfMonth() != 1) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_PERIOD);
        }
        Optional<CompanyWarehousePayrollRate> existingRate = rateRepository.findByCompanyIdAndEffectiveFrom(companyId, effectiveFrom);
        boolean hasExistingRate = rateRepository.existsByCompanyId(companyId);
        // Phiên bản đầu tiên được áp dụng ngay từ tháng này; các phiên bản sau chỉ từ tháng sau.
        LocalDate earliestAllowed = hasExistingRate
                ? YearMonth.now(VIETNAM_TIME_ZONE).plusMonths(1).atDay(1)
                : YearMonth.now(VIETNAM_TIME_ZONE).atDay(1);
        if (existingRate.isEmpty() && effectiveFrom.isBefore(earliestAllowed)) {
            throw new AppException(ErrorCode.PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY);
        }

        // Chỉ bảng đã chốt chặn sửa; bảng nháp dùng phiên bản này được tính lại sau khi lưu.
        periodRepository.findByCompanyIdAndPeriodStart(companyId, effectiveFrom)
                .filter(period -> period.getStatus() == PayrollPeriodStatus.LOCKED)
                .ifPresent(period -> { throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED); });
        if (existingRate.isPresent() && periodRepository.existsByCompanyIdAndPayrollRateIdAndStatus(
                companyId, existingRate.get().getId(), PayrollPeriodStatus.LOCKED)) {
            throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED);
        }
        List<WarehousePayrollPeriod> affectedDrafts = existingRate.isPresent()
                ? periodRepository.findByCompanyIdAndPayrollRateIdAndStatusOrderByPeriodStartAsc(
                        companyId, existingRate.get().getId(), PayrollPeriodStatus.DRAFT)
                : List.of();

        CompanyWarehousePayrollRate rate = existingRate
                .orElseGet(() -> CompanyWarehousePayrollRate.builder()
                        .company(companyReference(companyId))
                        .effectiveFrom(effectiveFrom)
                        .build());
        rate.setBaseSalaryVnd(request.baseSalaryVnd());
        rate.setRateVndPerKg(request.rateVndPerKg());
        rate.setKpiTargetKg(kpiTargetKg);
        rate.setKpiBonusVnd(kpiBonusVnd);

        CompanyWarehousePayrollRate savedRate = rateRepository.save(rate);
        for (WarehousePayrollPeriod draft : affectedDrafts) {
            calculatePeriod(companyId, YearMonth.from(draft.getPeriodStart()).toString());
        }
        return rateResponse(savedRate, periodRepository.findByCompanyIdAndPeriodStart(companyId, savedRate.getEffectiveFrom())
                .map(WarehousePayrollPeriod::getStatus).orElse(null));
    }

    @Override
    public WarehouseBaseSalaryResponse setWorkerBaseSalary(Long companyId, Long workerId, WarehouseBaseSalaryRequest request) {
        LocalDate nextMonth = YearMonth.now(VIETNAM_TIME_ZONE).plusMonths(1).atDay(1);
        LocalDate effectiveFrom = request.effectiveFrom() != null ? request.effectiveFrom() : nextMonth;
        if (effectiveFrom.getDayOfMonth() != 1) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_PERIOD);
        }
        if (effectiveFrom.isBefore(nextMonth)) {
            throw new AppException(ErrorCode.PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY);
        }
        User worker = userRepository.findById(workerId)
                .filter(user -> isCompanyWorker(user, companyId))
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Optional<WarehouseBaseSalaryOverride> current = baseSalaryOverrideRepository
                .findFirstByWorkerIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(workerId, effectiveFrom);
        BigDecimal companyBase = rateRepository
                .findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(companyId, effectiveFrom)
                .map(CompanyWarehousePayrollRate::getBaseSalaryVnd)
                .orElse(BigDecimal.ZERO);
        // Lương cứng thực nhận của tháng đó trước khi lưu: số lớn hơn giữa mức riêng đang có và mức chung của công ty.
        BigDecimal before = current.map(WarehouseBaseSalaryOverride::getBaseSalaryVnd)
                .map(raise -> raise.max(companyBase)).orElse(companyBase).setScale(0, RoundingMode.HALF_UP);

        WarehouseBaseSalaryOverride override = current
                .filter(existing -> existing.getEffectiveFrom().equals(effectiveFrom))
                .orElseGet(() -> WarehouseBaseSalaryOverride.builder()
                        .company(companyReference(companyId))
                        .worker(worker)
                        .effectiveFrom(effectiveFrom)
                        .build());
        override.setBaseSalaryVnd(request.baseSalaryVnd());
        override = baseSalaryOverrideRepository.save(override);

        BigDecimal after = override.getBaseSalaryVnd().max(companyBase).setScale(0, RoundingMode.HALF_UP);
        if (after.compareTo(before) > 0) {
            notifyBaseSalaryRaised(worker, effectiveFrom, before, after);
        }
        return new WarehouseBaseSalaryResponse(override.getId(), worker.getId(), worker.getFullName(),
                override.getEffectiveFrom(), override.getBaseSalaryVnd());
    }

    /** Báo nhân viên kho lương cứng thực nhận vừa tăng; gửi sau khi lưu xong, gửi lỗi chỉ ghi log. */
    private void notifyBaseSalaryRaised(User worker, LocalDate effectiveFrom, BigDecimal before, BigDecimal after) {
        if (brevoClient == null || worker.getEmail() == null || worker.getEmail().isBlank()) return;
        String recipient = worker.getEmail();
        String month = String.format("%02d/%d", effectiveFrom.getMonthValue(), effectiveFrom.getYear());
        NumberFormat money = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"));
        String name = HtmlUtils.htmlEscape(worker.getFullName() != null ? worker.getFullName() : recipient);
        String companyName = worker.getCompany() != null && worker.getCompany().getCompanyName() != null
                ? HtmlUtils.htmlEscape(worker.getCompany().getCompanyName()) : "";
        String previous = before.signum() > 0 ? " (trước đây " + money.format(before) + " đ)" : "";
        String subject = "LoadMaster - Lương cứng của bạn được điều chỉnh từ tháng " + month;
        String content = "Xin chào <strong>" + name + "</strong>,\n\n"
                + (companyName.isEmpty() ? "Công ty" : "Công ty <strong>" + companyName + "</strong>")
                + " vừa điều chỉnh lương cứng của bạn.\n\n"
                + "Từ tháng " + month + ", lương cứng của bạn là <strong>" + money.format(after) + " đ</strong>" + previous + ".\n\n"
                + "Bạn có thể xem chi tiết trong mục Phiếu lương trên ứng dụng của công ty.\n\n"
                + "Trân trọng,\nĐội ngũ LoadMaster";
        AfterCommit.run("email tăng lương cứng riêng của nhân viên kho", () -> {
            try {
                brevoClient.sendEmail(recipient, subject, content);
            } catch (RuntimeException ex) {
                log.warn("Không gửi được email tăng lương cứng cho nhân viên kho {}: {}", worker.getId(), ex.getMessage());
            }
        });
    }

    @Override
    public WarehousePayrollPeriodResponse calculatePeriod(Long companyId, String period) {
        lockCompanyForPayrollUpdate(companyId);
        YearMonth month = parsePeriod(period);
        LocalDate periodStart = month.atDay(1);
        CompanyWarehousePayrollRate rate = rateRepository
                .findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(companyId, periodStart)
                .orElseThrow(() -> new AppException(ErrorCode.PAYROLL_RATE_NOT_CONFIGURED));

        WarehousePayrollPeriod payrollPeriod = periodRepository.findByCompanyIdAndPeriodStartForUpdate(companyId, periodStart)
                .orElseGet(() -> WarehousePayrollPeriod.builder()
                        .company(companyReference(companyId))
                        .periodStart(periodStart)
                        .status(PayrollPeriodStatus.DRAFT)
                        .build());
        if (payrollPeriod.getStatus() == PayrollPeriodStatus.LOCKED) {
            throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED);
        }
        payrollPeriod.setPayrollRate(rate);
        if (payrollPeriod.getId() != null) {
            payrollPeriod.getWorkerPayrolls().clear();
            periodRepository.flush();
        }

        // Mọi nhân viên kho đang làm của công ty đều có một dòng, kể cả tháng không xếp kiện nào (chỉ lương cứng).
        Map<Long, WarehousePayroll> payrollByWorker = new LinkedHashMap<>();
        userRepository.findByCompanyIdAndUserRoleTypeAndStatus(companyId, UserRole.WAREHOUSE_WORKER, UserStatus.ACTIVE)
                .stream()
                .sorted(Comparator.comparing(User::getId))
                .forEach(worker -> payrollByWorker.put(worker.getId(), newWorkerPayroll(payrollPeriod, worker, rate, true)));

        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        Map<Long, Map<Long, WarehousePayrollTripLine>> linesByWorker = new LinkedHashMap<>();
        for (PlacementConfirmation confirmation : placementConfirmationRepository.findValidWarehouseLoadings(companyId, start, end)) {
            User worker = confirmation.getConfirmedBy();
            // Người đã nghỉ vẫn giữ phần việc đã làm trong tháng, nhưng không có lương cứng (như tài xế).
            WarehousePayroll payroll = payrollByWorker.computeIfAbsent(worker.getId(),
                    ignored -> newWorkerPayroll(payrollPeriod, worker, rate, worker.getStatus() == UserStatus.ACTIVE));
            Trip trip = confirmation.getPlacement().getLoadPlan().getJob().getTrip();
            BigDecimal weight = packageWeight(confirmation.getPlacement().getCargoPackage());
            LocalDate loadedDate = confirmation.getConfirmedAt().toLocalDate();

            WarehousePayrollTripLine line = linesByWorker
                    .computeIfAbsent(worker.getId(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(trip.getId(), ignored -> {
                        WarehousePayrollTripLine created = WarehousePayrollTripLine.builder()
                                .workerPayroll(payroll)
                                .trip(trip)
                                .loadedDate(loadedDate)
                                .loadedPackages(0)
                                .loadedKg(BigDecimal.ZERO)
                                .build();
                        payroll.getTrips().add(created);
                        return created;
                    });
            line.setLoadedPackages(line.getLoadedPackages() + 1);
            line.setLoadedKg(line.getLoadedKg().add(weight));
            if (loadedDate.isAfter(line.getLoadedDate())) line.setLoadedDate(loadedDate);
            payroll.setLoadedPackages(payroll.getLoadedPackages() + 1);
            payroll.setLoadedKg(payroll.getLoadedKg().add(weight));
        }

        // Tiền theo kg và KPI cần tổng kg cả tháng nên tính sau khi đã cộng hết các lần xếp.
        payrollByWorker.values().forEach(payroll -> settle(payroll, rate));
        payrollPeriod.getWorkerPayrolls().addAll(payrollByWorker.values());
        periodRepository.save(payrollPeriod);
        return periodResponse(payrollPeriod);
    }

    /** Lương tháng = lương cứng + kg đã xếp × đơn giá + thưởng KPI (đạt khi tổng kg không nhỏ hơn chỉ tiêu). */
    private void settle(WarehousePayroll payroll, CompanyWarehousePayrollRate rate) {
        payroll.getTrips().forEach(line -> line.setLoadedKg(line.getLoadedKg().setScale(3, RoundingMode.HALF_UP)));
        BigDecimal loadedKg = payroll.getLoadedKg().setScale(3, RoundingMode.HALF_UP);
        BigDecimal loadedAmount = PayrollMath.loadedAmount(loadedKg, rate.getRateVndPerKg());
        BigDecimal target = rate.getKpiTargetKg();
        BigDecimal bonus = PayrollMath.kpiBonus(loadedKg, target, rate.getKpiBonusVnd());
        payroll.setLoadedKg(loadedKg);
        payroll.setLoadedAmountVnd(loadedAmount);
        payroll.setKpiAchieved(target != null && loadedKg.compareTo(target) >= 0);
        payroll.setKpiBonusVnd(bonus);
        payroll.setTotalSalaryVnd(payroll.getBaseSalaryVnd().add(loadedAmount).add(bonus));
    }

    /** Khối lượng thật của kiện nếu đã cân, không thì khối lượng kế hoạch theo loại kiện (như phương án xếp). */
    private BigDecimal packageWeight(CargoPackage cargoPackage) {
        if (cargoPackage == null) throw new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE);
        BigDecimal weight = cargoPackage.getActualWeightKg();
        if (weight == null && cargoPackage.getPackageType() != null) {
            weight = cargoPackage.getPackageType().getWeightKg();
        }
        if (weight == null || weight.signum() < 0) {
            throw new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE);
        }
        return weight;
    }

    @Override
    @Transactional(readOnly = true)
    public WarehousePayrollPeriodResponse getPeriod(Long companyId, String period) {
        return periodResponse(findPeriod(companyId, parsePeriod(period).atDay(1)));
    }

    @Override
    @Transactional(readOnly = true)
    public WarehousePayrollStatementResponse getCompanyWorkerStatement(Long companyId, Long workerId, String period) {
        User worker = companyWorker(companyId, workerId);
        return statement(companyId, worker, parsePeriod(period));
    }

    @Override
    @Transactional(readOnly = true)
    public WarehousePayrollStatementResponse getWorkerStatement(Long companyId, Long workerId, String period) {
        User worker = companyWorker(companyId, workerId);
        YearMonth month = parsePeriod(period);
        LocalDate periodStart = month.atDay(1);
        WarehousePayrollPeriod payrollPeriod = periodRepository.findByCompanyIdAndPeriodStart(companyId, periodStart).orElse(null);
        if (payrollPeriod != null && payrollPeriod.getStatus() == PayrollPeriodStatus.LOCKED) {
            return statement(companyId, worker, month);
        }
        // Chưa chốt: nhân viên kho chỉ thấy lương cứng của tháng; kg đã xếp, tiền theo kg và KPI hiện sau khi chốt.
        PayrollPeriodStatus status = payrollPeriod == null ? null : payrollPeriod.getStatus();
        BigDecimal baseSalary = rateRepository
                .findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(companyId, periodStart)
                .map(rate -> workerBaseSalary(worker.getId(), periodStart, rate))
                .orElse(null);
        return new WarehousePayrollStatementResponse(false, month.toString(), status, worker.getId(), worker.getFullName(),
                baseSalary, null, null, null, null, null, null, null, null, List.of());
    }

    @Override
    public WarehousePayrollPeriodResponse lockPeriod(Long companyId, String period) {
        lockCompanyForPayrollUpdate(companyId);
        WarehousePayrollPeriod payrollPeriod = findPeriodForUpdate(companyId, parsePeriod(period).atDay(1));
        // Chỉ chốt kỳ này; các tháng đã chốt trước đó giữ nguyên.
        if (payrollPeriod.getStatus() != PayrollPeriodStatus.LOCKED) {
            payrollPeriod.setStatus(PayrollPeriodStatus.LOCKED);
            payrollPeriod.setLockedAt(LocalDateTime.now(VIETNAM_TIME_ZONE).truncatedTo(ChronoUnit.SECONDS));
            periodRepository.save(payrollPeriod);
        }
        return periodResponse(payrollPeriod);
    }

    @Override
    public WarehousePayrollPeriodResponse unlockPeriod(Long companyId, String period) {
        lockCompanyForPayrollUpdate(companyId);
        WarehousePayrollPeriod payrollPeriod = findPeriodForUpdate(companyId, parsePeriod(period).atDay(1));
        if (payrollPeriod.getStatus() == PayrollPeriodStatus.LOCKED) {
            payrollPeriod.setStatus(PayrollPeriodStatus.DRAFT);
            payrollPeriod.setLockedAt(null);
            periodRepository.save(payrollPeriod);
        }
        return periodResponse(payrollPeriod);
    }

    @Override
    public void deleteDraftPeriod(Long companyId, String period) {
        WarehousePayrollPeriod payrollPeriod = findPeriodForUpdate(companyId, parsePeriod(period).atDay(1));
        if (payrollPeriod.getStatus() == PayrollPeriodStatus.LOCKED) {
            throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED);
        }
        periodRepository.delete(payrollPeriod);
    }

    /** Lương cứng của tháng: số lớn hơn giữa mức riêng gần nhất tới tháng này và mức chung của công ty, làm tròn đồng. */
    private BigDecimal workerBaseSalary(Long workerId, LocalDate periodStart, CompanyWarehousePayrollRate rate) {
        BigDecimal companyBase = rate.getBaseSalaryVnd();
        BigDecimal base = baseSalaryOverrideRepository
                .findFirstByWorkerIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(workerId, periodStart)
                .map(WarehouseBaseSalaryOverride::getBaseSalaryVnd)
                .map(raise -> raise.max(companyBase))
                .orElse(companyBase);
        return base.setScale(0, RoundingMode.HALF_UP);
    }

    private WarehousePayroll newWorkerPayroll(
            WarehousePayrollPeriod period, User worker, CompanyWarehousePayrollRate rate, boolean includeBaseSalary) {
        BigDecimal baseSalary = includeBaseSalary
                ? workerBaseSalary(worker.getId(), period.getPeriodStart(), rate)
                : BigDecimal.ZERO;
        return WarehousePayroll.builder()
                .payrollPeriod(period)
                .worker(worker)
                .baseSalaryVnd(baseSalary)
                .loadedPackages(0)
                .loadedKg(BigDecimal.ZERO.setScale(3))
                .loadedAmountVnd(BigDecimal.ZERO)
                .totalSalaryVnd(baseSalary)
                .build();
    }

    private WarehousePayrollStatementResponse statement(Long companyId, User worker, YearMonth month) {
        WarehousePayrollPeriod payrollPeriod = periodRepository.findByCompanyIdAndPeriodStart(companyId, month.atDay(1)).orElse(null);
        if (payrollPeriod == null) {
            return emptyStatement(month, null, worker);
        }
        WarehousePayroll payroll = workerPayrollRepository
                .findByPayrollPeriodIdAndWorkerId(payrollPeriod.getId(), worker.getId()).orElse(null);
        if (payroll == null) {
            return emptyStatement(month, payrollPeriod.getStatus(), worker);
        }
        CompanyWarehousePayrollRate rate = payrollPeriod.getPayrollRate();
        List<WarehousePayrollTripLineResponse> lines = payroll.getTrips().stream()
                .sorted(Comparator.comparing(WarehousePayrollTripLine::getLoadedDate)
                        .thenComparing(line -> line.getTrip().getId()))
                .map(line -> new WarehousePayrollTripLineResponse(line.getTrip().getId(), line.getTrip().getTripCode(),
                        line.getLoadedDate(), line.getLoadedPackages(), line.getLoadedKg()))
                .toList();
        return new WarehousePayrollStatementResponse(true, month.toString(), payrollPeriod.getStatus(), worker.getId(),
                worker.getFullName(), payroll.getBaseSalaryVnd(), payroll.getLoadedPackages(), payroll.getLoadedKg(),
                rate.getRateVndPerKg(), payroll.getLoadedAmountVnd(), rate.getKpiTargetKg(), payroll.isKpiAchieved(),
                payroll.getKpiBonusVnd(), payroll.getTotalSalaryVnd(), lines);
    }

    private WarehousePayrollStatementResponse emptyStatement(YearMonth month, PayrollPeriodStatus status, User worker) {
        return new WarehousePayrollStatementResponse(false, month.toString(), status, worker.getId(), worker.getFullName(),
                null, null, null, null, null, null, null, null, null, List.of());
    }

    private WarehousePayrollPeriodResponse periodResponse(WarehousePayrollPeriod period) {
        List<WarehousePayrollSummaryResponse> workers = period.getWorkerPayrolls().stream()
                .sorted(Comparator.comparing(payroll -> payroll.getWorker().getId()))
                .map(payroll -> new WarehousePayrollSummaryResponse(
                        payroll.getWorker().getId(), payroll.getWorker().getFullName(), payroll.getBaseSalaryVnd(),
                        payroll.getLoadedPackages(), payroll.getLoadedKg(), payroll.getLoadedAmountVnd(),
                        payroll.isKpiAchieved(), payroll.getKpiBonusVnd(), payroll.getTotalSalaryVnd()))
                .toList();
        return new WarehousePayrollPeriodResponse(YearMonth.from(period.getPeriodStart()).toString(), period.getStatus(), workers);
    }

    private CompanyWarehousePayrollRateResponse rateResponse(CompanyWarehousePayrollRate rate, PayrollPeriodStatus periodStatus) {
        return new CompanyWarehousePayrollRateResponse(rate.getId(), rate.getEffectiveFrom(), rate.getBaseSalaryVnd(),
                rate.getRateVndPerKg(), rate.getKpiTargetKg(), rate.getKpiBonusVnd(), periodStatus);
    }

    private WarehousePayrollPeriod findPeriod(Long companyId, LocalDate periodStart) {
        return periodRepository.findByCompanyIdAndPeriodStart(companyId, periodStart)
                .orElseThrow(() -> new AppException(ErrorCode.PAYROLL_PERIOD_NOT_FOUND));
    }

    private WarehousePayrollPeriod findPeriodForUpdate(Long companyId, LocalDate periodStart) {
        return periodRepository.findByCompanyIdAndPeriodStartForUpdate(companyId, periodStart)
                .orElseThrow(() -> new AppException(ErrorCode.PAYROLL_PERIOD_NOT_FOUND));
    }

    private User companyWorker(Long companyId, Long workerId) {
        return userRepository.findById(workerId)
                .filter(user -> isCompanyWorker(user, companyId))
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    private boolean isCompanyWorker(User user, Long companyId) {
        return user != null
                && user.getCompany() != null
                && Objects.equals(user.getCompany().getId(), companyId)
                && user.getUserRoleType() == UserRole.WAREHOUSE_WORKER;
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

    private Company companyReference(Long companyId) {
        return Company.builder().id(companyId).build();
    }

    private void lockCompanyForPayrollUpdate(Long companyId) {
        companyRepository.findByIdForPayrollUpdate(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
    }
}
