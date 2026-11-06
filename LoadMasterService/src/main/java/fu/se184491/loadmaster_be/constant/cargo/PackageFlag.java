package fu.se184491.loadmaster_be.constant.cargo;

/**
 * Cờ của kiện bị bỏ khỏi chuyến lúc kho làm việc: {@code NOT_FOUND} — kho báo thiếu lúc soạn và điều phối viên bỏ kiện khỏi chuyến;
 * {@code DAMAGED} — kho báo hỏng lúc xếp. Kiện mang cờ không vào chuyến nào khác tới khi điều phối viên gỡ cờ.
 */
public enum PackageFlag { NOT_FOUND, DAMAGED }
