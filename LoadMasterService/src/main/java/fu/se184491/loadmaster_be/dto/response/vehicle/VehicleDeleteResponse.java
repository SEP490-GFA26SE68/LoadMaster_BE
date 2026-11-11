package fu.se184491.loadmaster_be.dto.response.vehicle;

/**
 * Kết quả xoá xe: {@code DELETED} khi xe chưa từng chạy chuyến nào (xoá hẳn), {@code RETIRED} khi xe còn trong lịch sử
 * các chuyến đã giao / đã huỷ (giữ bản ghi, ngừng sử dụng).
 */
public record VehicleDeleteResponse(Long id, String outcome) {
    public static final String DELETED = "DELETED";
    public static final String RETIRED = "RETIRED";
}
