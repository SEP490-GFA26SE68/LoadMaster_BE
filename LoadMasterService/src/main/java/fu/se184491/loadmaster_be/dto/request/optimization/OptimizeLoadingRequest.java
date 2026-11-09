package fu.se184491.loadmaster_be.dto.request.optimization;

/**
 * Thân tuỳ chọn của {@code POST /api/trips/{tripId}/optimize-loading}.
 * {@code keepPinsFromPlanId}: giữ nguyên các kiện đã ghim của phương án này (cùng chuyến) trong cả ba phương án
 * của lần chạy; vắng là lần chạy bình thường.
 */
public record OptimizeLoadingRequest(Long keepPinsFromPlanId) {
}
