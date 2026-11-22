package fu.se184491.loadmaster_be.service.assistant.briefing;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sự kiện của bản tin dưới dạng JSON gửi cho mô hình: chỉ số, mã chuyến và trạng thái, không chữ người dùng nhập. */
final class BriefingFactsJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BriefingFactsJson() {
    }

    static String of(BriefingFacts facts, UserRole role, LocalDate today) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", today.toString());
        data.put("role", role.name());
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Map.Entry<TripStatus, Long> entry : facts.tripsToday().entrySet()) {
            byStatus.put(entry.getKey().name(), entry.getValue());
        }
        data.put("tripsToday", byStatus);
        data.put("tripsTodayTotal", facts.tripsTodayTotal());
        data.put("lateRiskStops", facts.lateRiskStops());
        data.put("openExceptions", facts.openExceptions());
        data.put("escalatedExceptions", facts.escalatedExceptions());
        data.put("pendingManualConfirmations", facts.pendingManualConfirmations());
        putIfPresent(data, "tripsAwaitingApproval", facts.tripsAwaitingApproval());
        putIfPresent(data, "tripsWithStalePlan", facts.tripsWithStalePlan());
        putIfPresent(data, "creditsBalance", facts.creditsBalance());
        putIfPresent(data, "lowCreditThreshold", facts.lowCreditThreshold());
        if (facts.creditsBalance() != null && facts.lowCreditThreshold() != null) {
            data.put("creditsLow", facts.creditsLow());
        }
        List<Map<String, Object>> trips = facts.attentionTrips().stream().map(BriefingFactsJson::trip).toList();
        data.put("attentionTrips", trips);
        return JSON.writeValueAsString(data);
    }

    private static Map<String, Object> trip(BriefingFacts.AttentionTrip trip) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tripCode", trip.tripCode());
        data.put("escalatedExceptions", trip.escalatedExceptions());
        data.put("openExceptions", trip.openExceptions());
        data.put("lateRiskStops", trip.lateRiskStops());
        data.put("pendingManualConfirmations", trip.pendingManualConfirmations());
        data.put("awaitingApproval", trip.awaitingApproval());
        data.put("stalePlan", trip.stalePlan());
        return data;
    }

    private static void putIfPresent(Map<String, Object> data, String key, Long value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
