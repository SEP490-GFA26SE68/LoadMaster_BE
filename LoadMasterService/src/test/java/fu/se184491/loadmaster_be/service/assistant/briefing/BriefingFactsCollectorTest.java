package fu.se184491.loadmaster_be.service.assistant.briefing;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmReason;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.monitoring.DeadlineStatus;
import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionStatus;
import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionType;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.credit.CreditPricingResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.monitoring.EtaRiskAlert;
import fu.se184491.loadmaster_be.entity.monitoring.TripException;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Hai công ty thật trên H2: mọi số của bản tin chỉ đếm dòng của công ty người hỏi. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-briefing;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class BriefingFactsCollectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);

    @Autowired private EntityManager entityManager;
    @Autowired private BriefingFactsCollector collector;

    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private CreditService creditService;

    private Company longBinh;
    private Company phuongNam;
    private Company empty;

    @BeforeEach
    void seed() {
        longBinh = company("BRF-LB");
        phuongNam = company("BRF-PN");
        empty = company("BRF-EMPTY");
        seedCompany(longBinh, "LB");
        seedCompany(phuongNam, "PN");
        // Chỉ Phương Nam có thêm việc: số của Long Bình không được đổi theo
        Trip extra = trip(phuongNam, "TRIP-PN-X", TripStatus.IN_TRANSIT, TODAY.atTime(6, 0));
        exception(extra, TripExceptionStatus.ESCALATED);
        exception(extra, TripExceptionStatus.OPEN);
        trip(phuongNam, "TRIP-PN-Y", TripStatus.DRAFT, TODAY.atTime(15, 0));
        entityManager.flush();

        when(creditService.getPricing(anyLong())).thenReturn(pricing(false, 750));
        when(creditService.getBalance(anyLong())).thenReturn(balance(500));
    }

    @Test
    void countsOnlyRowsOfTheCallersCompany() {
        BriefingFacts facts = collector.collect(longBinh.getId(), UserRole.DISPATCHER, TODAY);

        assertThat(facts.tripsToday()).isEqualTo(Map.of(TripStatus.IN_TRANSIT, 1L, TripStatus.PLANNED, 2L,
                TripStatus.LOADING, 1L));
        assertThat(facts.tripsTodayTotal()).isEqualTo(4);
        assertThat(facts.lateRiskStops()).isEqualTo(1);
        assertThat(facts.openExceptions()).isEqualTo(1);
        assertThat(facts.escalatedExceptions()).isZero();
        assertThat(facts.pendingManualConfirmations()).isEqualTo(2);
        assertThat(facts.tripsAwaitingApproval()).isEqualTo(1);
        assertThat(facts.tripsWithStalePlan()).isEqualTo(1);
        assertThat(facts.creditsBalance()).isEqualTo(500);
        assertThat(facts.lowCreditThreshold()).isEqualTo(750);
        assertThat(facts.creditsLow()).isTrue();
        assertThat(facts.attentionTrips()).extracting(BriefingFacts.AttentionTrip::tripCode)
                .containsExactly("TRIP-LB-RUN", "TRIP-LB-LOAD", "TRIP-LB-STALE", "TRIP-LB-WAIT")
                .noneMatch(code -> code.contains("PN"));
        BriefingFacts.AttentionTrip running = facts.attentionTrips().getFirst();
        assertThat(running.openExceptions()).isEqualTo(1);
        assertThat(running.lateRiskStops()).isEqualTo(1);
        assertThat(running.pendingManualConfirmations()).isEqualTo(1);
    }

    @Test
    void otherCompanyGetsItsOwnNumbers() {
        BriefingFacts facts = collector.collect(phuongNam.getId(), UserRole.DISPATCHER, TODAY);

        assertThat(facts.tripsTodayTotal()).isEqualTo(6);
        assertThat(facts.openExceptions()).isEqualTo(3);
        assertThat(facts.escalatedExceptions()).isEqualTo(1);
        assertThat(facts.attentionTrips().getFirst().tripCode()).isEqualTo("TRIP-PN-X");
    }

    @Test
    void managerDoesNotGetPlanApprovalNumbers() {
        BriefingFacts facts = collector.collect(longBinh.getId(), UserRole.MANAGER, TODAY);

        assertThat(facts.tripsAwaitingApproval()).isNull();
        assertThat(facts.tripsWithStalePlan()).isNull();
        assertThat(facts.attentionTrips()).extracting(BriefingFacts.AttentionTrip::tripCode)
                .containsExactly("TRIP-LB-RUN", "TRIP-LB-LOAD");
    }

    @Test
    void emptyDayHasOnlyZerosAndNoTrips() {
        when(creditService.getPricing(empty.getId())).thenReturn(pricing(false, 750));
        when(creditService.getBalance(empty.getId())).thenReturn(balance(3000));

        BriefingFacts facts = collector.collect(empty.getId(), UserRole.DISPATCHER, TODAY);

        assertThat(facts.tripsToday()).isEmpty();
        assertThat(facts.lateRiskStops()).isZero();
        assertThat(facts.openExceptions()).isZero();
        assertThat(facts.escalatedExceptions()).isZero();
        assertThat(facts.pendingManualConfirmations()).isZero();
        assertThat(facts.tripsAwaitingApproval()).isZero();
        assertThat(facts.tripsWithStalePlan()).isZero();
        assertThat(facts.attentionTrips()).isEmpty();
        assertThat(facts.quiet()).isTrue();
    }

    @Test
    void tripsOfOtherDaysAreNotTripsOfToday() {
        BriefingFacts tomorrow = collector.collect(longBinh.getId(), UserRole.DISPATCHER, TODAY.plusDays(1));

        assertThat(tomorrow.tripsToday()).isEqualTo(Map.of(TripStatus.DRAFT, 1L));
    }

    @Test
    void unlimitedPlanHasNoBalanceAndNoThreshold() {
        when(creditService.getPricing(longBinh.getId())).thenReturn(pricing(true, null));

        BriefingFacts facts = collector.collect(longBinh.getId(), UserRole.MANAGER, TODAY);

        assertThat(facts.creditsBalance()).isNull();
        assertThat(facts.lowCreditThreshold()).isNull();
        assertThat(facts.creditsLow()).isFalse();
        verify(creditService, never()).getBalance(longBinh.getId());
    }

    /**
     * Mỗi công ty: chuyến đang chạy có một điểm nguy cơ trễ, một sự cố mở, một xác nhận dỡ chờ; chuyến đang xếp có một
     * xác nhận xếp chờ; một chuyến chờ duyệt, một chuyến chỉ còn phương án lỗi thời; một chuyến nháp ngày mai. Thêm các
     * dòng không được tính: cảnh báo cho hạn cũ, điểm đã tới, sự cố đã xử lý, xác nhận đã duyệt, xác nhận sai pha.
     */
    private void seedCompany(Company company, String code) {
        Trip running = trip(company, "TRIP-" + code + "-RUN", TripStatus.IN_TRANSIT, TODAY.atTime(7, 0));
        DeliveryStop late = stop(running, 1, DeliveryStopStatus.PENDING, TODAY.atTime(10, 0), null);
        DeliveryStop renegotiated = stop(running, 2, DeliveryStopStatus.PENDING, TODAY.atTime(16, 0), null);
        DeliveryStop arrived = stop(running, 3, DeliveryStopStatus.ARRIVED, TODAY.atTime(9, 0), TODAY.atTime(8, 50));
        alert(running, late, DeadlineStatus.AT_RISK, TODAY.atTime(10, 0));
        alert(running, late, DeadlineStatus.MISSED, TODAY.atTime(10, 0));
        alert(running, renegotiated, DeadlineStatus.MISSED, TODAY.atTime(12, 0));
        alert(running, arrived, DeadlineStatus.AT_RISK, TODAY.atTime(9, 0));
        exception(running, TripExceptionStatus.OPEN);
        exception(running, TripExceptionStatus.RESOLVED);
        manual(running, late, ManualConfirmStatus.PENDING, null);
        manual(running, late, ManualConfirmStatus.APPROVED, ManualConfirmStep.UNLOADING);
        manual(running, late, ManualConfirmStatus.PENDING, ManualConfirmStep.LOADING);

        Trip loading = trip(company, "TRIP-" + code + "-LOAD", TripStatus.LOADING, TODAY.atTime(8, 0));
        DeliveryStop loadingStop = stop(loading, 1, DeliveryStopStatus.PENDING, TODAY.atTime(13, 0), null);
        manual(loading, loadingStop, ManualConfirmStatus.PENDING, ManualConfirmStep.STAGING);
        manual(loading, loadingStop, ManualConfirmStatus.PENDING, ManualConfirmStep.UNLOADING);

        Trip waiting = trip(company, "TRIP-" + code + "-WAIT", TripStatus.PLANNED, TODAY.atTime(13, 0));
        OptimizationJob waitingJob = job(waiting);
        plan(waitingJob, false, null);
        plan(waitingJob, false, false);

        Trip stale = trip(company, "TRIP-" + code + "-STALE", TripStatus.PLANNED, TODAY.atTime(14, 0));
        OptimizationJob staleJob = job(stale);
        plan(staleJob, false, true);
        plan(staleJob, false, true);

        Trip approved = trip(company, "TRIP-" + code + "-OK", TripStatus.PLANNED, TODAY.minusDays(1).atTime(14, 0));
        OptimizationJob approvedJob = job(approved);
        plan(approvedJob, true, null);
        plan(approvedJob, false, null);

        trip(company, "TRIP-" + code + "-NEXT", TripStatus.DRAFT, TODAY.plusDays(1).atTime(7, 0));
    }

    private Company company(String code) {
        Company company = Company.builder().companyCode(code).companyName("Công ty " + code).taxCode(code + "-TAX")
                .billingEmail(code.toLowerCase() + "@loadmaster.test").status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now()).build();
        entityManager.persist(company);
        User dispatcher = User.builder().keycloakId(code + "-dispatcher").company(company)
                .email(code.toLowerCase() + "-dispatcher@loadmaster.test").fullName("Điều phối " + code)
                .userRoleType(UserRole.DISPATCHER).status(UserStatus.ACTIVE).build();
        entityManager.persist(dispatcher);
        VehicleType type = VehicleType.builder().company(company).name("Xe 3,5 tấn").innerLength(5200)
                .innerWidth(2000).innerHeight(2000).maxPayloadKg(new BigDecimal("3500")).build();
        entityManager.persist(type);
        entityManager.persist(Vehicle.builder().company(company).vehicleType(type).name("Xe 3,5 tấn")
                .licensePlate(code + "-51C").build());
        return company;
    }

    private Trip trip(Company company, String code, TripStatus status, LocalDateTime departure) {
        Vehicle vehicle = entityManager.createQuery("SELECT v FROM Vehicle v WHERE v.company = :c", Vehicle.class)
                .setParameter("c", company).getSingleResult();
        User dispatcher = entityManager.createQuery("SELECT u FROM User u WHERE u.company = :c", User.class)
                .setParameter("c", company).getSingleResult();
        Trip trip = Trip.builder().company(company).vehicle(vehicle).createdByDispatcher(dispatcher).tripCode(code)
                .tripName("Tuyến " + code).status(status).departureTime(departure).build();
        entityManager.persist(trip);
        return trip;
    }

    private DeliveryStop stop(Trip trip, int sequence, DeliveryStopStatus status, LocalDateTime plannedArrival,
                              LocalDateTime actualArrival) {
        DeliveryStop stop = DeliveryStop.builder().trip(trip).stopSequence(sequence).stopName("Điểm " + sequence)
                .address("Điểm " + sequence + ", Đồng Nai").status(status).plannedArrival(plannedArrival)
                .actualArrival(actualArrival).build();
        entityManager.persist(stop);
        return stop;
    }

    private void alert(Trip trip, DeliveryStop stop, DeadlineStatus status, LocalDateTime deadline) {
        entityManager.persist(EtaRiskAlert.builder().trip(trip).stopId(stop.getId())
                .stopNumber(stop.getStopSequence()).status(status).eta(deadline.plusMinutes(20)).deadline(deadline)
                .createdAt(TODAY.atTime(8, 0)).build());
    }

    private void exception(Trip trip, TripExceptionStatus status) {
        entityManager.persist(TripException.builder().trip(trip).type(TripExceptionType.TRAFFIC).delayMinutes(30)
                .status(status).reportedAt(TODAY.atTime(9, 0)).build());
    }

    private void manual(Trip trip, DeliveryStop stop, ManualConfirmStatus status, ManualConfirmStep step) {
        CargoPackage cargoPackage = CargoPackage.builder().company(trip.getCompany())
                .trackingBarcode(trip.getTripCode() + "-" + System.nanoTime()).actualWeightKg(BigDecimal.ONE).build();
        entityManager.persist(cargoPackage);
        entityManager.persist(ManualUnloadConfirmation.builder().trip(trip).deliveryStop(stop)
                .cargoPackage(cargoPackage).reason(ManualConfirmReason.QR_UNREADABLE).status(status).step(step)
                .requestedAt(TODAY.atTime(9, 30)).build());
    }

    private OptimizationJob job(Trip trip) {
        OptimizationJob job = OptimizationJob.builder().trip(trip).jobUuid("job-" + trip.getTripCode()).build();
        entityManager.persist(job);
        return job;
    }

    private void plan(OptimizationJob job, boolean approved, Boolean stale) {
        entityManager.persist(LoadPlan.builder().job(job).planName("Phương án").approved(approved).stale(stale)
                .build());
    }

    private static CreditPricingResponse pricing(boolean unlimited, Integer threshold) {
        return CreditPricingResponse.builder().unlimited(unlimited).lowBalanceThreshold(threshold).build();
    }

    private static CreditBalanceResponse balance(int value) {
        return CreditBalanceResponse.builder().balance(value).isUnlimited(false).build();
    }
}
