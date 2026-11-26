package fu.se184491.loadmaster_be.dto.response.optimization;

import java.util.List;

/**
 * Một cảnh báo dữ liệu của lần chạy tối ưu (spec ràng buộc vật lý 8.2, KE-HOACH-TOI-UU P8).
 *
 * @param code       TOP_LOAD_CAPACITY_MISSING (kiện chưa có σ) | PACKAGE_TYPE_MISSING (kiện không gắn loại kiện, bị cấm
 *                   xoay) | mã khác do OptimizeService thêm sau
 * @param packageIds mã kiện liên quan
 */
public record DataWarningResponse(String code, List<String> packageIds) {
}
