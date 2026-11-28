package fu.se184491.loadmaster_be.service.assistant.model;

/** Chuyến, phương án, kiện đang chọn trên màn 3D, do trình duyệt gửi lên. Chưa kiểm định dạng. */
public record ViewerContext(String tripCode, String loadPlanId, String selectedPackageCode) {
}
