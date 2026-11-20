package fu.se184491.loadmaster_be.service.assistant.briefing;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Sự kiện của bản tin "Tóm tắt hôm nay", do code đếm từ DB của đúng công ty người hỏi. Mô hình chỉ viết câu từ các số
 * này; số nào không có ở đây thì câu chứa số đó bị bỏ.
 *
 * @param tripsToday                 số chuyến xuất phát hôm nay (giờ Việt Nam) theo trạng thái; chỉ trạng thái có chuyến
 * @param lateRiskStops              điểm chưa tới của chuyến đang vận chuyển đã có cảnh báo nguy cơ trễ hạn cho hạn hiện tại
 * @param openExceptions             sự cố chưa xử lý (mở hoặc đã chuyển quản lý) của chuyến đang vận chuyển
 * @param escalatedExceptions        phần của {@code openExceptions} đã chuyển quản lý
 * @param pendingManualConfirmations xác nhận tay chờ điều phối viên duyệt, đúng bước theo pha của chuyến
 * @param tripsAwaitingApproval      chuyến đã lập kế hoạch có phương án chưa duyệt; {@code null} khi người hỏi không duyệt
 * @param tripsWithStalePlan         chuyến đã lập kế hoạch chỉ còn phương án lỗi thời; {@code null} như trên
 * @param creditsBalance             số dư credit; {@code null} khi gói không giới hạn
 * @param lowCreditThreshold         ngưỡng sắp hết credit; {@code null} khi gói không có ngưỡng
 * @param attentionTrips             tối đa {@link BriefingFactsCollector#MAX_ATTENTION_TRIPS} chuyến cần chú ý, nặng trước
 */
public record BriefingFacts(
        Map<TripStatus, Long> tripsToday,
        long lateRiskStops,
        long openExceptions,
        long escalatedExceptions,
        long pendingManualConfirmations,
        Long tripsAwaitingApproval,
        Long tripsWithStalePlan,
        Long creditsBalance,
        Long lowCreditThreshold,
        List<AttentionTrip> attentionTrips
) {

    public BriefingFacts {
        EnumMap<TripStatus, Long> byStatus = new EnumMap<>(TripStatus.class);
        byStatus.putAll(tripsToday);
        tripsToday = Collections.unmodifiableMap(byStatus);
        attentionTrips = List.copyOf(attentionTrips);
    }

    /** Một chuyến cần chú ý và số việc của nó. */
    public record AttentionTrip(
            String tripCode,
            long escalatedExceptions,
            long openExceptions,
            long lateRiskStops,
            long pendingManualConfirmations,
            boolean awaitingApproval,
            boolean stalePlan
    ) {
    }

    public long tripsTodayTotal() {
        return tripsToday.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Số dư đã xuống tới ngưỡng sắp hết credit (cùng phép so với thông báo "Sắp hết credit"). */
    public boolean creditsLow() {
        return creditsBalance != null && lowCreditThreshold != null && creditsBalance <= lowCreditThreshold;
    }

    /** Không có chuyến hôm nay, không việc nào chờ, credit không thấp: bản tin là câu cố định, không gọi mô hình. */
    public boolean quiet() {
        return tripsTodayTotal() == 0 && lateRiskStops == 0 && openExceptions == 0 && pendingManualConfirmations == 0
                && zero(tripsAwaitingApproval) && zero(tripsWithStalePlan) && attentionTrips.isEmpty() && !creditsLow();
    }

    /** Mọi số có trong sự kiện: câu của mô hình chỉ được dùng các số này. */
    public List<Long> numbers() {
        List<Long> all = new ArrayList<>(tripsToday.values());
        all.add(tripsTodayTotal());
        all.add(lateRiskStops);
        all.add(openExceptions);
        all.add(escalatedExceptions);
        all.add(pendingManualConfirmations);
        addIfPresent(all, tripsAwaitingApproval);
        addIfPresent(all, tripsWithStalePlan);
        addIfPresent(all, creditsBalance);
        addIfPresent(all, lowCreditThreshold);
        all.add((long) attentionTrips.size());
        for (AttentionTrip trip : attentionTrips) {
            all.add(trip.escalatedExceptions());
            all.add(trip.openExceptions());
            all.add(trip.lateRiskStops());
            all.add(trip.pendingManualConfirmations());
        }
        return all;
    }

    private static boolean zero(Long value) {
        return value == null || value == 0;
    }

    private static void addIfPresent(List<Long> all, Long value) {
        if (value != null) {
            all.add(value);
        }
    }
}
