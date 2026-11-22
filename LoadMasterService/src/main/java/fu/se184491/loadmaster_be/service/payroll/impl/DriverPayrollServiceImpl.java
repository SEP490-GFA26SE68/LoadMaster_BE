package fu.se184491.loadmaster_be.service.payroll.impl;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.payroll.CompanyPayrollRateRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.DriverBaseSalaryRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.SetTripPayrollDriversRequest;
import fu.se184491.loadmaster_be.dto.response.payroll.CompanyPayrollRateResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DriverBaseSalaryResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollDriverSummaryResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollPeriodResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollStatementResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollTripLineResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.TripPayrollDriverResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.payroll.CompanyPayrollRate;
import fu.se184491.loadmaster_be.entity.payroll.DriverBaseSalaryOverride;
import fu.se184491.loadmaster_be.entity.payroll.DriverPayroll;
import fu.se184491.loadmaster_be.entity.payroll.DriverPayrollTripLine;
import fu.se184491.loadmaster_be.entity.payroll.PayrollPeriod;
import fu.se184491.loadmaster_be.entity.payroll.TripPayrollDriverShare;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.AfterCommit;
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
import fu.se184491.loadmaster_be.service.payroll.DriverPayrollService;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class DriverPayrollServiceImpl implements DriverPayrollService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final ZoneId VIETNAM_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final CompanyPayrollRateRepository rateRepository;
    private final CompanyRepository companyRepository;
    private final DriverBaseSalaryOverrideRepository baseSalaryOverrideRepository;
    private final PayrollPeriodRepository periodRepository;
    private final DriverPayrollRepository driverPayrollRepository;
    private final TripPayrollDriverShareRepository shareRepository;
    private final TripRepository tripRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final LoadPlanRepository loadPlanRepository;
    private final PackagePlacementRepository packagePlacementRepository;
    private final UserRepository userRepository;
    private final BrevoClient brevoClient;

    @Override
    @Transactional(readOnly = true)
    public List<CompanyPayrollRateResponse> getRates(Long companyId) {
        List<CompanyPayrollRate> rates = rateRepository.findByCompanyIdOrderByEffectiveFromDesc(companyId);
        if (rates.isEmpty()) return List.of();
        Map<LocalDate, PayrollPeriodStatus> statusByStart = periodRepository
                .findByCompanyIdAndPeriodStartIn(
                        companyId, rates.stream().map(CompanyPayrollRate::getEffectiveFrom).toList())
                .stream()
                .collect(Collectors.toMap(PayrollPeriod::getPeriodStart, PayrollPeriod::getStatus, (first, ignored) -> first));
        return rates.stream()
                .map(rate -> rateResponse(rate, statusByStart.get(rate.getEffectiveFrom())))
                .toList();
    }

    @Override
    public CompanyPayrollRateResponse createRate(Long companyId, CompanyPayrollRateRequest request) {
        lockCompanyForPayrollUpdate(companyId);
        // KPI tuỳ chọn: không có chỉ tiêu là không dùng KPI (tiền thưởng về 0); có chỉ tiêu thì phải có tiền thưởng lớn hơn 0.
        BigDecimal kpiTargetKg = request.kpiTargetKg();
        BigDecimal kpiBonusVnd = BigDecimal.ZERO;
        if (kpiTargetKg != null) {
            // Tiền thưởng trả theo đồng (làm tròn HALF_UP): số làm tròn về 0 đồng thì tài xế đạt vẫn không được gì.
            if (request.kpiBonusVnd() == null || request.kpiBonusVnd().setScale(0, RoundingMode.HALF_UP).signum() <= 0) {
                throw new AppException(ErrorCode.PAYROLL_KPI_BONUS_REQUIRED);
            }
            kpiBonusVnd = request.kpiBonusVnd();
        }
        LocalDate effectiveFrom = request.effectiveFrom();
        if (effectiveFrom.getDayOfMonth() != 1) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_PERIOD);
        }
        Optional<CompanyPayrollRate> existingRate = rateRepository.findByCompanyIdAndEffectiveFrom(companyId, effectiveFrom);
        boolean hasExistingRate = rateRepository.existsByCompanyId(companyId);
        LocalDate earliestAllowed = hasExistingRate
                ? YearMonth.now(VIETNAM_TIME_ZONE).plusMonths(1).atDay(1)
                : YearMonth.now(VIETNAM_TIME_ZONE).atDay(1);
        if (existingRate.isEmpty() && effectiveFrom.isBefore(earliestAllowed)) {
            throw new AppException(ErrorCode.PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY);
        }

        // Chỉ bảng lương đã chốt mới chặn sửa; bảng nháp được tính lại ở bên dưới sau khi lưu mức lương.
        periodRepository.findByCompanyIdAndPeriodStart(companyId, effectiveFrom)
                .filter(period -> period.getStatus() == PayrollPeriodStatus.LOCKED)
                .ifPresent(period -> { throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED); });
        if (existingRate.isPresent() && periodRepository.existsByCompanyIdAndPayrollRateIdAndStatus(
                companyId, existingRate.get().getId(), PayrollPeriodStatus.LOCKED)) {
            throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED);
        }
        List<PayrollPeriod> affectedDrafts = existingRate.isPresent()
                ? periodRepository.findByCompanyIdAndPayrollRateIdAndStatusOrderByPeriodStartAsc(
                        companyId, existingRate.get().getId(), PayrollPeriodStatus.DRAFT)
                : List.of();

        CompanyPayrollRate rate = existingRate
                .orElseGet(() -> CompanyPayrollRate.builder()
                        .company(companyReference(companyId))
                        .effectiveFrom(effectiveFrom)
                        .build());

        rate.setBaseSalaryVnd(request.baseSalaryVnd());
        rate.setRateVndPerKg(request.rateVndPerKg());
        rate.setCommissionPercent(request.commissionPercent());
        rate.setHandlingFeeVndPerKg(request.handlingFeeVndPerKg());
        rate.setHandlingDriverPercent(request.handlingDriverPercent());
        rate.setKpiTargetKg(kpiTargetKg);
        rate.setKpiBonusVnd(kpiBonusVnd);

        CompanyPayrollRate savedRate = rateRepository.save(rate);
        for (PayrollPeriod draft : affectedDrafts) {
            calculatePeriod(companyId, YearMonth.from(draft.getPeriodStart()).toString());
        }
        return rateResponse(savedRate, periodRepository.findByCompanyIdAndPeriodStart(companyId, savedRate.getEffectiveFrom())
                .map(PayrollPeriod::getStatus).orElse(null));
    }

    @Override
    public DriverBaseSalaryResponse setDriverBaseSalary(Long companyId, Long driverId, DriverBaseSalaryRequest request) {
        if (request.effectiveFrom().getDayOfMonth() != 1) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_PERIOD);
        }
        if (request.effectiveFrom().isBefore(YearMonth.now(VIETNAM_TIME_ZONE).plusMonths(1).atDay(1))) {
            throw new AppException(ErrorCode.PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY);
        }
        User driver = userRepository.findById(driverId)
                .filter(u -> isCompanyDriver(u, companyId))
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Optional<DriverBaseSalaryOverride> current = baseSalaryOverrideRepository
                .findFirstByDriverIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(driverId, request.effectiveFrom());
        BigDecimal companyBase = rateRepository
                .findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(companyId, request.effectiveFrom())
                .map(CompanyPayrollRate::getBaseSalaryVnd)
                .orElse(BigDecimal.ZERO);
        // Lương cứng thực nhận của tháng đó trước khi lưu: số lớn hơn giữa mức riêng đang có và mức chung của công ty.
        BigDecimal before = current.map(DriverBaseSalaryOverride::getBaseSalaryVnd)
                .map(raise -> raise.max(companyBase)).orElse(companyBase).setScale(0, RoundingMode.HALF_UP);

        DriverBaseSalaryOverride override = current
                .filter(o -> o.getEffectiveFrom().equals(request.effectiveFrom()))
                .orElseGet(() -> DriverBaseSalaryOverride.builder()
                        .company(companyReference(companyId))
                        .driver(driver)
                        .effectiveFrom(request.effectiveFrom())
                        .build());

        override.setBaseSalaryVnd(request.baseSalaryVnd());
        override = baseSalaryOverrideRepository.save(override);

        BigDecimal after = override.getBaseSalaryVnd().max(companyBase).setScale(0, RoundingMode.HALF_UP);
        if (after.compareTo(before) > 0) {
            notifyBaseSalaryRaised(driver, request.effectiveFrom(), before, after);
        }

        return new DriverBaseSalaryResponse(override.getId(), driver.getId(),
                driver.getFullName(), override.getEffectiveFrom(), override.getBaseSalaryVnd());
    }

    /**
     * Báo cho tài xế biết lương cứng thực nhận của họ vừa tăng. Gửi sau khi lưu xong; gửi mail lỗi chỉ ghi log,
     * không làm hỏng việc lưu mức lương.
     */
    private void notifyBaseSalaryRaised(User driver, LocalDate effectiveFrom, BigDecimal before, BigDecimal after) {
        if (brevoClient == null || driver.getEmail() == null || driver.getEmail().isBlank()) return;
        String recipient = driver.getEmail();
        String month = String.format("%02d/%d", effectiveFrom.getMonthValue(), effectiveFrom.getYear());
        NumberFormat money = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"));
        String name = HtmlUtils.htmlEscape(driver.getFullName() != null ? driver.getFullName() : recipient);
        String companyName = driver.getCompany() != null && driver.getCompany().getCompanyName() != null
                ? HtmlUtils.htmlEscape(driver.getCompany().getCompanyName()) : "";
        String previous = before.signum() > 0 ? " (trước đây " + money.format(before) + " đ)" : "";
        String subject = "LoadMaster - Lương cứng của bạn được điều chỉnh từ tháng " + month;
        String content = "Xin chào <strong>" + name + "</strong>,\n\n"
                + (companyName.isEmpty() ? "Công ty" : "Công ty <strong>" + companyName + "</strong>")
                + " vừa điều chỉnh lương cứng của bạn.\n\n"
                + "Từ tháng " + month + ", lương cứng của bạn là <strong>" + money.format(after) + " đ</strong>" + previous + ".\n\n"
                + "Bạn có thể xem chi tiết trong mục Bảng lương trên ứng dụng của công ty.\n\n"
                + "Trân trọng,\nĐội ngũ LoadMaster";
        AfterCommit.run("email tăng lương cứng riêng", () -> {
            try {
                brevoClient.sendEmail(recipient, subject, content);
            } catch (RuntimeException ex) {
                log.warn("Không gửi được email tăng lương cứng cho tài xế {}: {}", driver.getId(), ex.getMessage());
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<TripPayrollDriverResponse> getTripDrivers(Long companyId, Long tripId) {
        Trip trip = tripRepository.findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
        List<TripPayrollDriverShare> shares = shareRepository.findByTripId(tripId);
        if (!shares.isEmpty()) return shares.stream().map(this::shareResponse).toList();
        if (trip.getDriver() == null) throw new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE);
        return List.of(new TripPayrollDriverResponse(trip.getDriver().getId(), trip.getDriver().getFullName(), new BigDecimal("100.00")));
    }

    @Override
    public List<TripPayrollDriverResponse> setTripDrivers(
            Long companyId,
            Long tripId,
            SetTripPayrollDriversRequest request
    ) {
        Trip trip = tripRepository.findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
        List<Long> driverIds = request.driverIds();
        if (driverIds.stream().distinct().count() != driverIds.size()
                || trip.getDriver() == null
                || !driverIds.contains(trip.getDriver().getId())) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_DRIVER_ASSIGNMENT);
        }
        rejectAssignmentChangeForLockedPeriod(trip);

        List<User> drivers = userRepository.findAllById(driverIds);
        Map<Long, User> byId = drivers.stream().collect(Collectors.toMap(User::getId, user -> user));
        if (drivers.size() != driverIds.size() || driverIds.stream().anyMatch(id -> !isActiveCompanyDriver(byId.get(id), companyId))) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_DRIVER_ASSIGNMENT);
        }

        shareRepository.deleteByTripId(tripId);
        shareRepository.flush();
        List<BigDecimal> percentages = PayrollMath.equalSharePercentages(driverIds.size());
        List<TripPayrollDriverShare> saved = new ArrayList<>(driverIds.size());
        for (int i = 0; i < driverIds.size(); i++) {
            User driver = byId.get(driverIds.get(i));
            saved.add(shareRepository.save(TripPayrollDriverShare.builder()
                    .trip(trip)
                    .driver(driver)
                    .sharePercent(percentages.get(i))
                    .build()));
        }
        return saved.stream().map(this::shareResponse).toList();
    }

    @Override
    public PayrollPeriodResponse calculatePeriod(Long companyId, String period) {
        lockCompanyForPayrollUpdate(companyId);
        YearMonth month = parsePeriod(period);
        LocalDate periodStart = month.atDay(1);
        CompanyPayrollRate rate = rateRepository
                .findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(companyId, periodStart)
                .orElseThrow(() -> new AppException(ErrorCode.PAYROLL_RATE_NOT_CONFIGURED));

        PayrollPeriod payrollPeriod = periodRepository.findByCompanyIdAndPeriodStartForUpdate(companyId, periodStart)
                .orElseGet(() -> PayrollPeriod.builder()
                        .company(companyReference(companyId))
                        .periodStart(periodStart)
                        .status(PayrollPeriodStatus.DRAFT)
                        .build());
        if (payrollPeriod.getStatus() == PayrollPeriodStatus.LOCKED) {
            throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED);
        }
        payrollPeriod.setPayrollRate(rate);
        if (payrollPeriod.getId() != null) {
            payrollPeriod.getDriverPayrolls().clear();
            periodRepository.flush();
        }

        Map<Long, DriverPayroll> payrollByDriver = new LinkedHashMap<>();
        userRepository.findByCompanyIdAndUserRoleTypeAndStatus(companyId, UserRole.DRIVER, UserStatus.ACTIVE)
                .stream()
                .sorted(Comparator.comparing(User::getId))
                .forEach(driver -> payrollByDriver.put(driver.getId(), newDriverPayroll(payrollPeriod, driver, rate)));

        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        List<Trip> completedTrips = new ArrayList<>(
                tripRepository.findByCompanyIdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                        companyId, TripStatus.DELIVERED, start, end));
        // Fallback cho các chuyến DELIVERED cũ/seed bị trống completed_at: suy ra từ giờ hoàn tất/đến điểm cuối rồi backfill luôn.
        List<Trip> legacyDelivered = tripRepository.findByCompanyIdAndStatusAndCompletedAtIsNull(
                companyId, TripStatus.DELIVERED);
        if (legacyDelivered != null) {
            for (Trip legacyTrip : legacyDelivered) {
                LocalDateTime resolved = resolveEffectiveCompletedAt(legacyTrip);
                if (resolved != null) {
                    legacyTrip.setCompletedAt(resolved);
                    tripRepository.save(legacyTrip);
                    if (!resolved.isBefore(start) && resolved.isBefore(end)) {
                        completedTrips.add(legacyTrip);
                    }
                }
            }
        }
        for (Trip trip : completedTrips) {
            addTripToPayroll(trip, payrollPeriod, rate, payrollByDriver);
        }
        // KPI cần tổng kg cả tháng của từng tài xế nên tính sau khi đã cộng hết các chuyến.
        payrollByDriver.values().forEach(payroll -> applyKpi(payroll, rate));

        payrollPeriod.getDriverPayrolls().addAll(payrollByDriver.values());
        payrollRepositorySave(payrollPeriod);
        return periodResponse(payrollPeriod);
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollPeriodResponse getPeriod(Long companyId, String period) {
        PayrollPeriod payrollPeriod = findPeriod(companyId, parsePeriod(period).atDay(1));
        return periodResponse(payrollPeriod);
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollStatementResponse getCompanyDriverStatement(Long companyId, Long driverId, String period) {
        User driver = userRepository.findById(driverId)
                .filter(user -> isCompanyDriver(user, companyId))
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        return statement(companyId, driver, parsePeriod(period));
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollStatementResponse getDriverStatement(Long companyId, Long driverId, String period) {
        User driver = userRepository.findById(driverId)
                .filter(user -> isCompanyDriver(user, companyId))
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        YearMonth month = parsePeriod(period);
        LocalDate periodStart = month.atDay(1);
        PayrollPeriod payrollPeriod = periodRepository.findByCompanyIdAndPeriodStart(companyId, periodStart).orElse(null);
        if (payrollPeriod != null && payrollPeriod.getStatus() == PayrollPeriodStatus.LOCKED) {
            return statement(companyId, driver, month);
        }
        // Chưa chốt: tài xế chỉ thấy lương cứng của tháng; hoa hồng và bốc dỡ hiện sau khi chốt.
        PayrollPeriodStatus status = payrollPeriod == null ? null : payrollPeriod.getStatus();
        BigDecimal baseSalary = rateRepository
                .findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(companyId, periodStart)
                .map(rate -> driverBaseSalary(driver.getId(), periodStart, rate))
                .orElse(null);
        return new PayrollStatementResponse(false, month.toString(), status, driver.getId(), driver.getFullName(),
                baseSalary, null, null, null, null, null, null, null, null, null, null, null, List.of());
    }

    @Override
    public PayrollPeriodResponse lockPeriod(Long companyId, String period) {
        lockCompanyForPayrollUpdate(companyId);
        PayrollPeriod payrollPeriod = findPeriodForUpdate(companyId, parsePeriod(period).atDay(1));
        lockPeriodNow(payrollPeriod);
        return periodResponse(payrollPeriod);
    }

    @Override
    public PayrollPeriodResponse unlockPeriod(Long companyId, String period) {
        lockCompanyForPayrollUpdate(companyId);
        PayrollPeriod payrollPeriod = findPeriodForUpdate(companyId, parsePeriod(period).atDay(1));
        if (payrollPeriod.getStatus() == PayrollPeriodStatus.LOCKED) {
            payrollPeriod.setStatus(PayrollPeriodStatus.DRAFT);
            payrollPeriod.setLockedAt(null);
            payrollRepositorySave(payrollPeriod);
        }
        return periodResponse(payrollPeriod);
    }

    @Override
    public void deleteDraftPeriod(Long companyId, String period) {
        PayrollPeriod payrollPeriod = findPeriodForUpdate(companyId, parsePeriod(period).atDay(1));
        if (payrollPeriod.getStatus() == PayrollPeriodStatus.LOCKED) {
            throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED);
        }
        periodRepository.delete(payrollPeriod);
    }

    private void addTripToPayroll(
            Trip trip,
            PayrollPeriod payrollPeriod,
            CompanyPayrollRate rate,
            Map<Long, DriverPayroll> payrollByDriver
    ) {
        LoadPlan loadPlan = loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(trip.getId())
                .orElseThrow(() -> new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE));
        List<PackagePlacement> placements = packagePlacementRepository.findByLoadPlanId(loadPlan.getId());
        BigDecimal planKg = plannedWeight(placements);

        PayrollMath.TripAmounts tripAmounts = PayrollMath.calculateTripAmounts(
                planKg, rate.getRateVndPerKg(), rate.getCommissionPercent(),
                rate.getHandlingFeeVndPerKg(), rate.getHandlingDriverPercent());

        List<TripPayrollDriverShare> shares = shareRepository.findByTripId(trip.getId());
        if (shares.isEmpty()) {
            if (trip.getDriver() == null) throw new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE);
            shares = List.of(TripPayrollDriverShare.builder()
                    .trip(trip).driver(trip.getDriver()).sharePercent(new BigDecimal("100.00")).build());
        }
        validateShares(shares);

        List<BigDecimal> percentages = shares.stream().map(TripPayrollDriverShare::getSharePercent).toList();
        List<BigDecimal> allocatedKgShares = PayrollMath.allocateQuantityByPercent(planKg, percentages);
        List<BigDecimal> kgAmounts = PayrollMath.splitEvenly(tripAmounts.commissionAmount(), shares.size());
        List<BigDecimal> handlingAmounts = PayrollMath.splitEvenly(tripAmounts.handlingAmount(), shares.size());
        for (int i = 0; i < shares.size(); i++) {
            TripPayrollDriverShare share = shares.get(i);
            User driver = share.getDriver();
            // The active roster gets the fixed salary; a historical assignment keeps only its trip productivity.
            DriverPayroll payroll = payrollByDriver.computeIfAbsent(
                    driver.getId(), ignored -> newDriverPayroll(
                            payrollPeriod, driver, rate, driver.getStatus() == UserStatus.ACTIVE));
            BigDecimal allocatedKg = allocatedKgShares.get(i);
            BigDecimal kgAmount = kgAmounts.get(i);
            BigDecimal handlingAmount = handlingAmounts.get(i);
            DriverPayrollTripLine line = DriverPayrollTripLine.builder()
                    .driverPayroll(payroll)
                    .trip(trip)
                    .loadPlanId(loadPlan.getId())
                    .planKg(planKg)
                    .sharePercent(share.getSharePercent())
                    .allocatedKg(allocatedKg)
                    .rateVndPerKg(rate.getRateVndPerKg())
                    .kgAmountVnd(kgAmount)
                    .handlingAmountVnd(handlingAmount)
                    .totalAmountVnd(kgAmount.add(handlingAmount))
                    .build();
            payroll.getTrips().add(line);
            payroll.setTotalKg(payroll.getTotalKg().add(allocatedKg));
            payroll.setKgAmountVnd(payroll.getKgAmountVnd().add(kgAmount));
            payroll.setHandlingAmountVnd(payroll.getHandlingAmountVnd().add(handlingAmount));
            payroll.setTotalSalaryVnd(payroll.getBaseSalaryVnd()
                    .add(payroll.getKgAmountVnd()).add(payroll.getHandlingAmountVnd()));
        }
    }

    /**
     * Thưởng KPI theo kg: tài xế đạt khi tổng kg trong tháng không nhỏ hơn chỉ tiêu của công ty; hệ thống tự tính, không ai đánh dấu tay.
     * Lương tháng = lương cứng + hoa hồng + bốc dỡ + thưởng KPI.
     */
    private void applyKpi(DriverPayroll payroll, CompanyPayrollRate rate) {
        BigDecimal target = rate.getKpiTargetKg();
        BigDecimal bonus = PayrollMath.kpiBonus(payroll.getTotalKg(), target, rate.getKpiBonusVnd());
        payroll.setKpiAchieved(target != null && payroll.getTotalKg().compareTo(target) >= 0);
        payroll.setKpiAmountVnd(bonus);
        payroll.setTotalSalaryVnd(payroll.getBaseSalaryVnd()
                .add(payroll.getKgAmountVnd()).add(payroll.getHandlingAmountVnd()).add(bonus));
    }

    private BigDecimal plannedWeight(List<PackagePlacement> placements) {
        if (placements.isEmpty()) throw new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE);
        BigDecimal total = BigDecimal.ZERO;
        for (PackagePlacement placement : placements) {
            CargoPackage cargoPackage = placement.getCargoPackage();
            if (cargoPackage == null) throw new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE);
            BigDecimal weight = cargoPackage.getActualWeightKg();
            if (weight == null && cargoPackage.getPackageType() != null) {
                weight = cargoPackage.getPackageType().getWeightKg();
            }
            if (weight == null || weight.signum() < 0) {
                throw new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE);
            }
            total = total.add(weight);
        }
        return total.setScale(3, RoundingMode.HALF_UP);
    }

    private void validateShares(List<TripPayrollDriverShare> shares) {
        BigDecimal total = BigDecimal.ZERO;
        if (shares.stream().anyMatch(share -> share.getDriver() == null || share.getDriver().getId() == null)) {
            throw new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE);
        }
        Set<Long> ids = shares.stream().map(share -> share.getDriver().getId()).collect(Collectors.toSet());
        if (ids.size() != shares.size()) throw new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE);
        for (TripPayrollDriverShare share : shares) {
            if (share.getSharePercent() == null
                    || share.getSharePercent().signum() <= 0) {
                throw new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE);
            }
            total = total.add(share.getSharePercent());
        }
        if (total.compareTo(ONE_HUNDRED) != 0) throw new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE);
    }

    private DriverPayroll newDriverPayroll(PayrollPeriod period, User driver, CompanyPayrollRate rate) {
        return newDriverPayroll(period, driver, rate, true);
    }

    /**
     * Lương cứng của tài xế trong tháng: số lớn hơn giữa mức riêng đã đặt cho tài xế (gần nhất tới tháng này) và mức chung của công ty.
     * Công ty nâng mức chung vượt mức riêng thì tài xế cũng lên; mức riêng cao hơn thì giữ nguyên.
     */
    private BigDecimal driverBaseSalary(Long driverId, LocalDate periodStart, CompanyPayrollRate rate) {
        BigDecimal companyBase = rate.getBaseSalaryVnd();
        BigDecimal base = baseSalaryOverrideRepository
                .findFirstByDriverIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(driverId, periodStart)
                .map(DriverBaseSalaryOverride::getBaseSalaryVnd)
                .map(raise -> raise.max(companyBase))
                .orElse(companyBase);
        return base.setScale(0, RoundingMode.HALF_UP);
    }

    private DriverPayroll newDriverPayroll(PayrollPeriod period, User driver, CompanyPayrollRate rate, boolean includeBaseSalary) {
        BigDecimal baseSalary = BigDecimal.ZERO;
        if (includeBaseSalary) {
            baseSalary = driverBaseSalary(driver.getId(), period.getPeriodStart(), rate);
        }
        return DriverPayroll.builder()
                .payrollPeriod(period)
                .driver(driver)
                .baseSalaryVnd(baseSalary)
                .totalKg(BigDecimal.ZERO.setScale(3))
                .kgAmountVnd(BigDecimal.ZERO)
                .handlingAmountVnd(BigDecimal.ZERO)
                .totalSalaryVnd(baseSalary)
                .trips(new ArrayList<>())
                .build();
    }

    private PayrollStatementResponse statement(Long companyId, User driver, YearMonth month) {
        LocalDate periodStart = month.atDay(1);
        PayrollPeriod payrollPeriod = periodRepository.findByCompanyIdAndPeriodStart(companyId, periodStart).orElse(null);
        if (payrollPeriod == null) {
            return new PayrollStatementResponse(false, month.toString(), null, driver.getId(), driver.getFullName(),
                    null, null, null, null, null, null, null, null, null, null, null, null, List.of());
        }
        DriverPayroll payroll = driverPayrollRepository
                .findByPayrollPeriodIdAndDriverId(payrollPeriod.getId(), driver.getId()).orElse(null);
        if (payroll == null) {
            return new PayrollStatementResponse(false, month.toString(), payrollPeriod.getStatus(), driver.getId(), driver.getFullName(),
                    null, null, null, null, null, null, null, null, null, null, null, null, List.of());
        }
        CompanyPayrollRate rate = payrollPeriod.getPayrollRate();
        List<PayrollTripLineResponse> lines = payroll.getTrips().stream().map(this::lineResponse).toList();
        return new PayrollStatementResponse(true, month.toString(), payrollPeriod.getStatus(), driver.getId(), driver.getFullName(),
                payroll.getBaseSalaryVnd(), payroll.getTotalKg(), rate.getRateVndPerKg(), payroll.getKgAmountVnd(),
                rate.getCommissionPercent(), rate.getHandlingFeeVndPerKg(), rate.getHandlingDriverPercent(),
                payroll.getHandlingAmountVnd(), rate.getKpiTargetKg(), payroll.isKpiAchieved(), payroll.getKpiAmountVnd(),
                payroll.getTotalSalaryVnd(), lines);
    }

    private PayrollPeriod findPeriod(Long companyId, LocalDate periodStart) {
        return periodRepository.findByCompanyIdAndPeriodStart(companyId, periodStart)
                .orElseThrow(() -> new AppException(ErrorCode.PAYROLL_PERIOD_NOT_FOUND));
    }

    private PayrollPeriod findPeriodForUpdate(Long companyId, LocalDate periodStart) {
        return periodRepository.findByCompanyIdAndPeriodStartForUpdate(companyId, periodStart)
                .orElseThrow(() -> new AppException(ErrorCode.PAYROLL_PERIOD_NOT_FOUND));
    }

    private PayrollPeriodResponse periodResponse(PayrollPeriod period) {
        List<PayrollDriverSummaryResponse> drivers = period.getDriverPayrolls().stream()
                .sorted(Comparator.comparing(payroll -> payroll.getDriver().getId()))
                .map(payroll -> new PayrollDriverSummaryResponse(
                        payroll.getDriver().getId(), payroll.getDriver().getFullName(), payroll.getBaseSalaryVnd(),
                        payroll.getTotalKg(), payroll.getKgAmountVnd(), payroll.getHandlingAmountVnd(),
                        payroll.isKpiAchieved(), payroll.getKpiAmountVnd(), payroll.getTotalSalaryVnd()))
                .toList();
        return new PayrollPeriodResponse(YearMonth.from(period.getPeriodStart()).toString(), period.getStatus(), drivers);
    }

    private PayrollTripLineResponse lineResponse(DriverPayrollTripLine line) {
        return new PayrollTripLineResponse(line.getTrip().getId(), line.getTrip().getTripCode(), line.getPlanKg(),
                line.getSharePercent(), line.getAllocatedKg(),
                line.getKgAmountVnd(), line.getHandlingAmountVnd(), line.getTotalAmountVnd());
    }

    private List<TripPayrollDriverResponse> shareResponse(List<TripPayrollDriverShare> shares) {
        return shares.stream().map(this::shareResponse).toList();
    }

    private TripPayrollDriverResponse shareResponse(TripPayrollDriverShare share) {
        return new TripPayrollDriverResponse(share.getDriver().getId(), share.getDriver().getFullName(), share.getSharePercent());
    }

    private CompanyPayrollRateResponse rateResponse(CompanyPayrollRate rate, PayrollPeriodStatus periodStatus) {
        return new CompanyPayrollRateResponse(rate.getId(), rate.getEffectiveFrom(), rate.getBaseSalaryVnd(),
                rate.getRateVndPerKg(), rate.getCommissionPercent(), rate.getHandlingFeeVndPerKg(),
                rate.getHandlingDriverPercent(), rate.getKpiTargetKg(), rate.getKpiBonusVnd(), periodStatus);
    }

    /** Chỉ chốt kỳ này; các tháng đã chốt trước đó giữ nguyên để còn xem lại lương. */
    private void lockPeriodNow(PayrollPeriod payrollPeriod) {
        if (payrollPeriod.getStatus() != PayrollPeriodStatus.LOCKED) {
            payrollPeriod.setStatus(PayrollPeriodStatus.LOCKED);
            payrollPeriod.setLockedAt(LocalDateTime.now(VIETNAM_TIME_ZONE).truncatedTo(ChronoUnit.SECONDS));
            payrollRepositorySave(payrollPeriod);
        }
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

    private void rejectAssignmentChangeForLockedPeriod(Trip trip) {
        LocalDateTime effectiveCompletedAt = resolveEffectiveCompletedAt(trip);
        if (effectiveCompletedAt == null) return;
        LocalDate periodStart = effectiveCompletedAt.toLocalDate().withDayOfMonth(1);
        periodRepository.findByCompanyIdAndPeriodStartForUpdate(trip.getCompany().getId(), periodStart)
                .filter(period -> period.getStatus() == PayrollPeriodStatus.LOCKED)
                .ifPresent(period -> { throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED); });
    }

    /**
     * Thời điểm hoàn tất hiệu lực của chuyến DELIVERED: ưu tiên trips.completed_at; nếu null (dữ liệu cũ/seed)
     * thì lấy giờ hoàn tất muộn nhất hoặc giờ đến thực tế muộn nhất của các điểm dừng, cuối cùng fallback về departureTime.
     */
    private LocalDateTime resolveEffectiveCompletedAt(Trip trip) {
        if (trip.getCompletedAt() != null) {
            return trip.getCompletedAt();
        }
        if (trip.getStatus() != TripStatus.DELIVERED) {
            return null;
        }
        if (deliveryStopRepository != null && trip.getId() != null) {
            List<DeliveryStop> stops = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId());
            if (stops != null && !stops.isEmpty()) {
                LocalDateTime maxStopCompleted = stops.stream()
                        .map(DeliveryStop::getCompletedAt)
                        .filter(Objects::nonNull)
                        .max(LocalDateTime::compareTo)
                        .orElse(null);
                if (maxStopCompleted != null) {
                    return maxStopCompleted;
                }
                LocalDateTime maxActualArrival = stops.stream()
                        .map(DeliveryStop::getActualArrival)
                        .filter(Objects::nonNull)
                        .max(LocalDateTime::compareTo)
                        .orElse(null);
                if (maxActualArrival != null) {
                    return maxActualArrival;
                }
            }
        }
        return trip.getDepartureTime();
    }

    private boolean isActiveCompanyDriver(User user, Long companyId) {
        return user != null && isCompanyDriver(user, companyId) && user.getStatus() == UserStatus.ACTIVE;
    }

    private boolean isCompanyDriver(User user, Long companyId) {
        return user != null
                && user.getCompany() != null
                && Objects.equals(user.getCompany().getId(), companyId)
                && user.getUserRoleType() == UserRole.DRIVER;
    }

    private fu.se184491.loadmaster_be.entity.company.Company companyReference(Long companyId) {
        return fu.se184491.loadmaster_be.entity.company.Company.builder().id(companyId).build();
    }

    private void lockCompanyForPayrollUpdate(Long companyId) {
        companyRepository.findByIdForPayrollUpdate(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
    }

    private void payrollRepositorySave(PayrollPeriod period) {
        periodRepository.save(period);
    }
}
