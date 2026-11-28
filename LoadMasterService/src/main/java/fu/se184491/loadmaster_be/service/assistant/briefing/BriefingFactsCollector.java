package fu.se184491.loadmaster_be.service.assistant.briefing;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.monitoring.DeadlineStatus;
import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.credit.CreditPricingResponse;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.monitoring.EtaRiskAlertRepository;
import fu.se184491.loadmaster_be.repository.monitoring.TripExceptionRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.trip.TripCodeCount;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Đếm sự kiện của bản tin "Tóm tắt hôm nay" bằng truy vấn chỉ đọc, mọi truy vấn lọc theo công ty của người hỏi. Không
 * gọi màn giám sát (màn đó gọi dịch vụ chỉ đường và ghi DB): nguy cơ trễ hạn là cảnh báo đã lưu.
 */
@Component
public class BriefingFactsCollector {

    static final int MAX_ATTENTION_TRIPS = 5;

    /** Mã chuyến trên URL của web; mã khác dạng không vào bản tin (không dựng được liên kết). */
    private static final Pattern TRIP_CODE = Pattern.compile("^TRIP-[A-Z0-9-]{1,40}$");

    private static final Set<UserRole> PLAN_APPROVERS = EnumSet.of(UserRole.DISPATCHER);

    private static final Comparator<BriefingFacts.AttentionTrip> HEAVIEST_FIRST = Comparator
            .comparingLong(BriefingFacts.AttentionTrip::escalatedExceptions).reversed()
            .thenComparing(Comparator.comparingLong(BriefingFacts.AttentionTrip::openExceptions).reversed())
            .thenComparing(Comparator.comparingLong(BriefingFacts.AttentionTrip::lateRiskStops).reversed())
            .thenComparing(Comparator.comparingLong(BriefingFacts.AttentionTrip::pendingManualConfirmations).reversed())
            .thenComparing(BriefingFacts.AttentionTrip::stalePlan, Comparator.reverseOrder())
            .thenComparing(BriefingFacts.AttentionTrip::awaitingApproval, Comparator.reverseOrder())
            .thenComparing(BriefingFacts.AttentionTrip::tripCode);

    private final TripRepository trips;
    private final EtaRiskAlertRepository etaRiskAlerts;
    private final TripExceptionRepository exceptions;
    private final ManualUnloadConfirmationRepository manualConfirmations;
    private final LoadPlanRepository loadPlans;
    private final CreditService credits;

    public BriefingFactsCollector(TripRepository trips, EtaRiskAlertRepository etaRiskAlerts,
                                  TripExceptionRepository exceptions,
                                  ManualUnloadConfirmationRepository manualConfirmations,
                                  LoadPlanRepository loadPlans, CreditService credits) {
        this.trips = trips;
        this.etaRiskAlerts = etaRiskAlerts;
        this.exceptions = exceptions;
        this.manualConfirmations = manualConfirmations;
        this.loadPlans = loadPlans;
        this.credits = credits;
    }

    /** Sự kiện của {@code today} (ngày theo giờ Việt Nam) cho công ty {@code companyId}, theo vai trò người hỏi. */
    public BriefingFacts collect(Long companyId, UserRole role, LocalDate today) {
        Map<TripStatus, Long> tripsToday = new EnumMap<>(TripStatus.class);
        for (TripRepository.StatusCount row
                : trips.countDepartingBetweenByStatus(companyId, today.atStartOfDay(), today.plusDays(1).atStartOfDay())) {
            if (row.getStatus() != null && row.getTotal() != null && row.getTotal() > 0) {
                tripsToday.merge(row.getStatus(), row.getTotal(), Long::sum);
            }
        }

        Map<String, Long> lateRisk = byTrip(etaRiskAlerts.countStopsAtRiskByTrip(companyId, TripStatus.IN_TRANSIT,
                EnumSet.of(DeadlineStatus.AT_RISK, DeadlineStatus.MISSED), DeliveryStopStatus.COMPLETED));
        Map<String, Long> open = byTrip(exceptions.countByTripAndStatusIn(companyId, TripStatus.IN_TRANSIT,
                EnumSet.of(TripExceptionStatus.OPEN, TripExceptionStatus.ESCALATED)));
        Map<String, Long> escalated = byTrip(exceptions.countByTripAndStatusIn(companyId, TripStatus.IN_TRANSIT,
                EnumSet.of(TripExceptionStatus.ESCALATED)));
        Map<String, Long> manual = byTrip(manualConfirmations.countByTripMatchingPhase(companyId,
                ManualConfirmStatus.PENDING, TripStatus.IN_TRANSIT, ManualConfirmStep.UNLOADING, TripStatus.LOADING,
                EnumSet.of(ManualConfirmStep.STAGING, ManualConfirmStep.LOADING)));

        boolean approver = PLAN_APPROVERS.contains(role);
        Set<String> awaiting = approver
                ? validCodes(loadPlans.findTripCodesAwaitingApproval(companyId, TripStatus.PLANNED)) : Set.of();
        Set<String> stale = approver
                ? validCodes(loadPlans.findTripCodesWithOnlyStalePlans(companyId, TripStatus.PLANNED)) : Set.of();

        CreditPricingResponse pricing = credits.getPricing(companyId);
        boolean unlimited = pricing != null && Boolean.TRUE.equals(pricing.getUnlimited());
        Long balance = unlimited ? null : balanceOf(credits.getBalance(companyId));
        Long threshold = unlimited || pricing == null || pricing.getLowBalanceThreshold() == null
                ? null : pricing.getLowBalanceThreshold().longValue();

        return new BriefingFacts(tripsToday, sum(lateRisk), sum(open), sum(escalated), sum(manual),
                approver ? (long) awaiting.size() : null, approver ? (long) stale.size() : null, balance, threshold,
                attentionTrips(lateRisk, open, escalated, manual, awaiting, stale));
    }

    private static List<BriefingFacts.AttentionTrip> attentionTrips(
            Map<String, Long> lateRisk, Map<String, Long> open, Map<String, Long> escalated, Map<String, Long> manual,
            Set<String> awaiting, Set<String> stale) {
        Set<String> codes = new TreeSet<>();
        codes.addAll(lateRisk.keySet());
        codes.addAll(open.keySet());
        codes.addAll(manual.keySet());
        codes.addAll(awaiting);
        codes.addAll(stale);
        return codes.stream()
                .filter(code -> TRIP_CODE.matcher(code).matches())
                .map(code -> new BriefingFacts.AttentionTrip(code, escalated.getOrDefault(code, 0L),
                        open.getOrDefault(code, 0L), lateRisk.getOrDefault(code, 0L), manual.getOrDefault(code, 0L),
                        awaiting.contains(code), stale.contains(code)))
                .sorted(HEAVIEST_FIRST)
                .limit(MAX_ATTENTION_TRIPS)
                .toList();
    }

    private static Map<String, Long> byTrip(List<TripCodeCount> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (TripCodeCount row : rows) {
            if (row.getTripCode() != null && row.getTotal() != null && row.getTotal() > 0) {
                counts.merge(row.getTripCode(), row.getTotal(), Long::sum);
            }
        }
        return counts;
    }

    private static Set<String> validCodes(List<String> codes) {
        Set<String> valid = new TreeSet<>();
        for (String code : codes) {
            if (code != null) {
                valid.add(code);
            }
        }
        return valid;
    }

    private static long sum(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    private static Long balanceOf(CreditBalanceResponse balance) {
        return balance == null || balance.getBalance() == null ? null : balance.getBalance().longValue();
    }
}
