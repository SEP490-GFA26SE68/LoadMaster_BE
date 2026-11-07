package fu.se184491.loadmaster_be.constant.warehouse;

/**
 * Báo thiếu một kiện lúc soạn hàng: {@code OPEN} chờ điều phối viên quyết; {@code KEEP_SEARCHING} điều phối bảo kho tìm tiếp (kiện vẫn
 * phải soạn); {@code FOUND} kho tìm thấy và đã soạn kiện; {@code DROPPED} kiện bị bỏ khỏi chuyến.
 */
public enum ShortageStatus { OPEN, KEEP_SEARCHING, FOUND, DROPPED }
