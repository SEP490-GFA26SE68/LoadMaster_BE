package fu.se184491.loadmaster_be.service.payroll.impl;

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
import fu.se184491.loadmaster_be.entity.route.RoutePlan;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.payroll.CompanyPayrollRateRepository;
import fu.se184491.loadmaster_be.repository.payroll.DriverBaseSalaryOverrideRepository;
import fu.se184491.loadmaster_be.repository.payroll.DriverPayrollRepository;
import fu.se184491.loadmaster_be.repository.payroll.PayrollPeriodRepository;
import fu.se184491.loadmaster_be.repository.payroll.TripPayrollDriverShareRepository;
import fu.se184491.loadmaster_be.repository.route.RoutePlanRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.payroll.DriverPayrollService;
import fu.se184491.loadmaster_be.service.payroll.PayrollMath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class DriverPayrollServiceImpl implements DriverPayrollService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal METERS_PER_KILOMETER = new BigDecimal("1000");
    private static final ZoneId VIETNAM_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final CompanyPayrollRateRepository rateRepository;
    private final DriverBaseSalaryOverrideRepository baseSalaryOverrideRepository;
    private final PayrollPeriodRepository periodRepository;
    private final DriverPayrollRepository driverPayrollRepository;
    private final TripPayrollDriverShareRepository shareRepository;
    private final TripRepository tripRepository;
    private final RoutePlanRepository routePlanRepository;
    private final LoadPlanRepository loadPlanRepository;
    private final PackagePlacementRepository packagePlacementRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public List<CompanyPayrollRateResponse> getRates(Long companyId) {
        return rateRepository.findByCompanyIdOrderByEffectiveFromDesc(companyId).stream()
                .map(this::rateResponse)
                .toList();
    }

    @Override
    public CompanyPayrollRateResponse createRate(Long companyId, CompanyPayrollRateRequest request) {
        LocalDate effectiveFrom = request.effectiveFrom();
        if (effectiveFrom.getDayOfMonth() != 1) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_PERIOD);
        }
        boolean hasExistingRate = rateRepository.existsByCompanyId(companyId);
        LocalDate earliestAllowed = hasExistingRate
                ? YearMonth.now(VIETNAM_TIME_ZONE).plusMonths(1).atDay(1)
                : YearMonth.now(VIETNAM_TIME_ZONE).atDay(1);
        if (effectiveFrom.isBefore(earliestAllowed)) {
            throw new AppException(ErrorCode.PAYROLL_RATE_EFFECTIVE_DATE_TOO_EARLY);
        }

        periodRepository.findByCompanyIdAndPeriodStart(companyId, effectiveFrom)
                .filter(period -> period.getStatus() == PayrollPeriodStatus.LOCKED)
                .ifPresent(period -> { throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED); });

        CompanyPayrollRate rate = rateRepository.findByCompanyIdAndEffectiveFrom(companyId, effectiveFrom)
                .orElseGet(() -> CompanyPayrollRate.builder()
                        .company(companyReference(companyId))
                        .effectiveFrom(effectiveFrom)
                        .build());

        rate.setBaseSalaryVnd(request.baseSalaryVnd());
        rate.setRateVndPerKg(request.rateVndPerKg());
        rate.setRateVndPerKm(request.rateVndPerKm());

        return rateResponse(rateRepository.save(rate));
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

        DriverBaseSalaryOverride override = baseSalaryOverrideRepository
                .findFirstByDriverIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(driverId, request.effectiveFrom())
                .filter(o -> o.getEffectiveFrom().equals(request.effectiveFrom()))
                .orElseGet(() -> DriverBaseSalaryOverride.builder()
                        .company(companyReference(companyId))
                        .driver(driver)
                        .effectiveFrom(request.effectiveFrom())
                        .build());

        override.setBaseSalaryVnd(request.baseSalaryVnd());
        override = baseSalaryOverrideRepository.save(override);

        return new DriverBaseSalaryResponse(override.getId(), driver.getId(),
                driver.getFullName(), override.getEffectiveFrom(), override.getBaseSalaryVnd());
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
        List<Trip> completedTrips = tripRepository.findByCompanyIdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                companyId, TripStatus.DELIVERED, start, end);
        for (Trip trip : completedTrips) {
            addTripToPayroll(trip, payrollPeriod, rate, payrollByDriver);
        }

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
        return statement(companyId, driver, parsePeriod(period));
    }

    @Override
    public PayrollPeriodResponse lockPeriod(Long companyId, String period) {
        PayrollPeriod payrollPeriod = findPeriod(companyId, parsePeriod(period).atDay(1));
        if (payrollPeriod.getStatus() != PayrollPeriodStatus.LOCKED) {
            payrollPeriod.setStatus(PayrollPeriodStatus.LOCKED);
            payrollPeriod.setLockedAt(LocalDateTime.now(VIETNAM_TIME_ZONE).truncatedTo(ChronoUnit.SECONDS));
            payrollRepositorySave(payrollPeriod);
        }
        return periodResponse(payrollPeriod);
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

        RoutePlan routePlan = routePlanRepository.findByTripIdAndIsActiveTrue(trip.getId())
                .filter(route -> route.getTotalDistanceMeters() != null && route.getTotalDistanceMeters() > 0)
                .orElseThrow(() -> new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE));
        BigDecimal planKm = BigDecimal.valueOf(routePlan.getTotalDistanceMeters())
                .divide(METERS_PER_KILOMETER, 3, RoundingMode.HALF_UP);
        PayrollMath.TripAmounts tripAmounts = PayrollMath.calculateTripAmounts(
                planKg, planKm, rate.getRateVndPerKg(), rate.getRateVndPerKm());

        List<TripPayrollDriverShare> shares = shareRepository.findByTripId(trip.getId());
        if (shares.isEmpty()) {
            if (trip.getDriver() == null) throw new AppException(ErrorCode.PAYROLL_DATA_INCOMPLETE);
            shares = List.of(TripPayrollDriverShare.builder()
                    .trip(trip).driver(trip.getDriver()).sharePercent(new BigDecimal("100.00")).build());
        }
        validateShares(shares);

        List<BigDecimal> percentages = shares.stream().map(TripPayrollDriverShare::getSharePercent).toList();
        List<BigDecimal> allocatedKgShares = PayrollMath.allocateQuantityByPercent(planKg, percentages);
        List<BigDecimal> allocatedKmShares = PayrollMath.allocateQuantityByPercent(planKm, percentages);
        List<BigDecimal> kgAmounts = PayrollMath.splitEvenly(tripAmounts.kgAmount(), shares.size());
        List<BigDecimal> kmAmounts = PayrollMath.splitEvenly(tripAmounts.kmAmount(), shares.size());
        for (int i = 0; i < shares.size(); i++) {
            TripPayrollDriverShare share = shares.get(i);
            User driver = share.getDriver();
            // The active roster gets the fixed salary; a historical assignment keeps only its trip productivity.
            DriverPayroll payroll = payrollByDriver.computeIfAbsent(
                    driver.getId(), ignored -> newDriverPayroll(
                            payrollPeriod, driver, rate, driver.getStatus() == UserStatus.ACTIVE));
            BigDecimal allocatedKg = allocatedKgShares.get(i);
            BigDecimal allocatedKm = allocatedKmShares.get(i);
            BigDecimal kgAmount = kgAmounts.get(i);
            BigDecimal kmAmount = kmAmounts.get(i);
            DriverPayrollTripLine line = DriverPayrollTripLine.builder()
                    .driverPayroll(payroll)
                    .trip(trip)
                    .loadPlanId(loadPlan.getId())
                    .routePlanId(routePlan.getId())
                    .planKg(planKg)
                    .planKm(planKm)
                    .sharePercent(share.getSharePercent())
                    .allocatedKg(allocatedKg)
                    .allocatedKm(allocatedKm)
                    .rateVndPerKg(rate.getRateVndPerKg())
                    .rateVndPerKm(rate.getRateVndPerKm())
                    .kgAmountVnd(kgAmount)
                    .kmAmountVnd(kmAmount)
                    .totalAmountVnd(kgAmount.add(kmAmount))
                    .build();
            payroll.getTrips().add(line);
            payroll.setTotalKg(payroll.getTotalKg().add(allocatedKg));
            payroll.setTotalKm(payroll.getTotalKm().add(allocatedKm));
            payroll.setKgAmountVnd(payroll.getKgAmountVnd().add(kgAmount));
            payroll.setKmAmountVnd(payroll.getKmAmountVnd().add(kmAmount));
            payroll.setTotalSalaryVnd(payroll.getBaseSalaryVnd()
                    .add(payroll.getKgAmountVnd()).add(payroll.getKmAmountVnd()));
        }
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

    private DriverPayroll newDriverPayroll(PayrollPeriod period, User driver, CompanyPayrollRate rate, boolean includeBaseSalary) {
        BigDecimal baseSalary = BigDecimal.ZERO;
        if (includeBaseSalary) {
            BigDecimal overrideOrCompany = baseSalaryOverrideRepository
                    .findFirstByDriverIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                            driver.getId(), period.getPeriodStart())
                    .map(DriverBaseSalaryOverride::getBaseSalaryVnd)
                    .orElse(rate.getBaseSalaryVnd());
            baseSalary = overrideOrCompany.setScale(0, RoundingMode.HALF_UP);
        }
        return DriverPayroll.builder()
                .payrollPeriod(period)
                .driver(driver)
                .baseSalaryVnd(baseSalary)
                .totalKg(BigDecimal.ZERO.setScale(3))
                .kgAmountVnd(BigDecimal.ZERO)
                .totalKm(BigDecimal.ZERO.setScale(3))
                .kmAmountVnd(BigDecimal.ZERO)
                .totalSalaryVnd(baseSalary)
                .trips(new ArrayList<>())
                .build();
    }

    private PayrollStatementResponse statement(Long companyId, User driver, YearMonth month) {
        LocalDate periodStart = month.atDay(1);
        PayrollPeriod payrollPeriod = periodRepository.findByCompanyIdAndPeriodStart(companyId, periodStart).orElse(null);
        if (payrollPeriod == null) {
            return new PayrollStatementResponse(false, month.toString(), null, driver.getId(), driver.getFullName(),
                    null, null, null, null, null, null, null, null, List.of());
        }
        DriverPayroll payroll = driverPayrollRepository
                .findByPayrollPeriodIdAndDriverId(payrollPeriod.getId(), driver.getId()).orElse(null);
        if (payroll == null) {
            return new PayrollStatementResponse(false, month.toString(), payrollPeriod.getStatus(), driver.getId(), driver.getFullName(),
                    null, null, null, null, null, null, null, null, List.of());
        }
        CompanyPayrollRate rate = payrollPeriod.getPayrollRate();
        List<PayrollTripLineResponse> lines = payroll.getTrips().stream().map(this::lineResponse).toList();
        return new PayrollStatementResponse(true, month.toString(), payrollPeriod.getStatus(), driver.getId(), driver.getFullName(),
                payroll.getBaseSalaryVnd(), payroll.getTotalKg(), rate.getRateVndPerKg(), payroll.getKgAmountVnd(),
                payroll.getTotalKm(), rate.getRateVndPerKm(), payroll.getKmAmountVnd(), payroll.getTotalSalaryVnd(), lines);
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
                        payroll.getTotalKg(), payroll.getKgAmountVnd(), payroll.getTotalKm(), payroll.getKmAmountVnd(),
                        payroll.getTotalSalaryVnd()))
                .toList();
        return new PayrollPeriodResponse(YearMonth.from(period.getPeriodStart()).toString(), period.getStatus(), drivers);
    }

    private PayrollTripLineResponse lineResponse(DriverPayrollTripLine line) {
        return new PayrollTripLineResponse(line.getTrip().getId(), line.getTrip().getTripCode(), line.getPlanKg(),
                line.getPlanKm(), line.getSharePercent(), line.getAllocatedKg(), line.getAllocatedKm(),
                line.getKgAmountVnd(), line.getKmAmountVnd(), line.getTotalAmountVnd());
    }

    private List<TripPayrollDriverResponse> shareResponse(List<TripPayrollDriverShare> shares) {
        return shares.stream().map(this::shareResponse).toList();
    }

    private TripPayrollDriverResponse shareResponse(TripPayrollDriverShare share) {
        return new TripPayrollDriverResponse(share.getDriver().getId(), share.getDriver().getFullName(), share.getSharePercent());
    }

    private CompanyPayrollRateResponse rateResponse(CompanyPayrollRate rate) {
        return new CompanyPayrollRateResponse(rate.getId(), rate.getEffectiveFrom(), rate.getBaseSalaryVnd(),
                rate.getRateVndPerKg(), rate.getRateVndPerKm());
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
        if (trip.getCompletedAt() == null) return;
        LocalDate periodStart = trip.getCompletedAt().toLocalDate().withDayOfMonth(1);
        periodRepository.findByCompanyIdAndPeriodStartForUpdate(trip.getCompany().getId(), periodStart)
                .filter(period -> period.getStatus() == PayrollPeriodStatus.LOCKED)
                .ifPresent(period -> { throw new AppException(ErrorCode.PAYROLL_PERIOD_LOCKED); });
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

    private void payrollRepositorySave(PayrollPeriod period) {
        periodRepository.save(period);
    }
}
