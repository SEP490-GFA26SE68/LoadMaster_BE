package fu.se184491.loadmaster_be.dto.response.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Báo cáo chuyến đã giao (hoặc bị huỷ khi đang vận chuyển): mọi số đếm từ dữ liệu đã ghi của kho và tài xế, không lưu riêng.
 * Mốc giờ là giờ Việt Nam không kèm múi giờ.
 */
@Builder
public record TripReportResponse(
        Long tripId,
        String tripCode,
        String tripName,
        TripStatus status,
        String vehicleName,
        String licensePlate,
        String driverName,
        LocalDateTime departureTime,
        /** Giờ tài xế hoàn tất điểm cuối; null khi chưa có. */
        LocalDateTime completedAt,
        /** Chuyến đã huỷ: lý do và trạng thái lúc huỷ; null khi chuyến không bị huỷ. */
        Cancellation cancellation,
        /** Phiên xếp hàng đã hoàn tất của chuyến; null khi kho chưa xếp xong. */
        Loading loading,
        /** Lý do cho chở chung kiện khác loại hàng; null khi chuyến không vượt luật phân tách. */
        String overrideReason,
        List<Stop> stops,
        Packages packages,
        /** Số kiện theo cách đối chiếu có hiệu lực ở từng bước: soạn, xếp, dỡ. */
        List<Verification> verifications,
        List<ManualConfirmation> manualConfirmations,
        List<TripExceptionItem> exceptions,
        List<Reroute> reroutes
) {

    @Builder
    public record Cancellation(String reason, TripStatus fromStatus) {
    }

    @Builder
    public record Loading(LocalDateTime startedAt, LocalDateTime completedAt, String sealNumber, LocalDateTime sealedAt) {
    }

    @Builder
    public record Stop(
            Long stopId,
            Integer sequence,
            String stopType,
            String name,
            String address,
            int packageCount,
            int unloaded,
            int returned,
            int issues,
            LocalDateTime plannedArrival,
            LocalDateTime actualArrival,
            LocalDateTime deadline,
            /** Mức hạn theo giờ đến kế hoạch: ON_TIME, AT_RISK, MISSED; null khi không có hạn hoặc không có giờ kế hoạch. */
            String plannedStatus,
            /** Đến thật kịp hạn không; null khi chưa đến hoặc điểm không có hạn. */
            Boolean arrivedOnTime,
            boolean completed
    ) {
    }

    @Builder
    public record Packages(int total, int staged, int loaded, int damaged, int delivered, int returned, int withIssue) {
    }

    /** {@code label}: đối chiếu bằng nhãn (quét hoặc gõ mã); {@code manual}: xác nhận tay chưa bị từ chối. */
    @Builder
    public record Verification(String step, int label, int manual) {
    }

    @Builder
    public record ManualConfirmation(
            Long id,
            String step,
            String packageCode,
            Integer stopSequence,
            String reason,
            String note,
            String status,
            String requestedBy,
            LocalDateTime requestedAt,
            String decidedBy,
            LocalDateTime decidedAt,
            String decisionNote
    ) {
    }

    @Builder
    public record TripExceptionItem(
            Long id,
            String type,
            String description,
            Integer delayMinutes,
            String status,
            Integer stopNumber,
            String reportedBy,
            LocalDateTime reportedAt,
            LocalDateTime escalatedAt,
            String resolvedBy,
            LocalDateTime resolvedAt,
            String resolutionNote
    ) {
    }

    @Builder
    public record Reroute(
            Integer stopNumber,
            String route,
            Double distanceKm,
            Integer durationMinutes,
            Long extraMs,
            LocalDateTime confirmedAt,
            String confirmedBy
    ) {
    }
}
