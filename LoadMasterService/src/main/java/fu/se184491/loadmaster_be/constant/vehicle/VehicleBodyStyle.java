package fu.se184491.loadmaster_be.constant.vehicle;

/**
 * Kiểu thân xe của loại xe: chỉ đổi hình xe trong khung 3D của web (Planner, kho, tài xế). Không vào yêu cầu tối ưu, đổi
 * không làm phương án lỗi thời. Loại xe chưa chọn (trống) thì web tự chọn theo kích thước lòng thùng.
 */
public enum VehicleBodyStyle {
    VAN_MINI_FLAT,
    VAN_MINI_HOOD,
    VAN_MID,
    VAN_LARGE,
    TRUCK_BOX,
    TRUCK_CANVAS,
    TRUCK_FLATBED,
    TRUCK_REEFER;

    /** Tên kiểu của loại xe cho phản hồi; null khi không có loại xe hoặc loại xe chưa chọn kiểu. */
    public static String nameOf(fu.se184491.loadmaster_be.entity.vehicle.VehicleType type) {
        return type == null || type.getBodyStyle() == null ? null : type.getBodyStyle().name();
    }
}
