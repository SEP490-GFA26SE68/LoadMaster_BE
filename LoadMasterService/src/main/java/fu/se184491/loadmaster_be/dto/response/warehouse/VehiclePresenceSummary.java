package fu.se184491.loadmaster_be.dto.response.warehouse;

/**
 * Xe của chuyến đang ở đâu theo kho ghi nhận (chỉ đọc): {@code status} là {@code UNKNOWN} | {@code AT_WAREHOUSE} |
 * {@code IN_TRANSIT}; {@code warehouseId} chỉ có khi {@code AT_WAREHOUSE}. Tài xế chỉ xuất phát được khi xe
 * {@code AT_WAREHOUSE} đúng kho xuất phát của chuyến; khác thì nhân viên kho xác nhận xe ở kho
 * ({@code POST /api/warehouse/vehicle-presence/{warehouseId}/loading-trips/{tripId}/confirm-presence}).
 */
public record VehiclePresenceSummary(String status, Long warehouseId) {
}
