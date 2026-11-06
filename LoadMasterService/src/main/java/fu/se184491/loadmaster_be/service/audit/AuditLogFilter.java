package fu.se184491.loadmaster_be.service.audit;

import java.time.LocalDate;

/**
 * Bộ lọc nhật ký. {@code from} / {@code to} là ngày theo giờ Việt Nam, khoảng đóng.
 * {@code q} tìm trong loại việc, đối tượng, mã đối tượng, tên và email người làm.
 */
public record AuditLogFilter(
        LocalDate from,
        LocalDate to,
        String actionType,
        String entityName,
        Long userId,
        String q
) {
}
