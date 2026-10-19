
package fu.se184491.loadmaster_be.dto.request;

import java.math.BigDecimal;

/**
 * Dữ liệu của một dòng trong file Excel nhập kiện.
 *
 * quantity là số kiện vật lý cần tạo.
 * Mỗi kiện được tạo sẽ có mã và QR riêng.
 *
 * Các thuộc tính xếp hàng sẽ được lưu vào
 * CargoPackagePackingProfile.
 */
public record PackageImportRow(

        // Vị trí dòng trong Excel
        int rowIndex,

        // 1. Mã kiện gốc
        String packageCode,

        // 2. Tên kiện
        String packageName,

        // 3-5. Kích thước, đơn vị cm
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,

        // 6. Khối lượng mỗi kiện, đơn vị kg
        BigDecimal weightKg,

        // 7. Số lượng kiện vật lý
        Integer quantity,

        // 8. Điểm đến: tên hoặc địa chỉ
        String destination,

        // 9. Hướng đặt được phép
        String orientation,

        // 10. Phải giữ thẳng đứng
        Boolean uprightOnly,

        // 11. Mức độ dễ vỡ
        Integer fragilityLevel,

        // 12. Cho phép xếp chồng
        Boolean stackable,

        // 13. Tải trọng đặt phía trên tối đa, kg
        BigDecimal maxTopLoadKg,

        // 14. Số tầng xếp tối đa
        Integer maxLayers,

        // 15. Tỷ lệ hỗ trợ đáy tối thiểu (0-1)
        BigDecimal minSupportRatio,

        // 16. Độ ưu tiên
        Integer priority,

        // 17. Bắt buộc xếp
        Boolean mustLoad,

        // 18. Nhóm kiện
        String packingGroup,

        // 19. Ghi chú
        String note,

        // 20. Loại hàng: STANDARD, FRAGILE,...
        String handlingClass,

        // 21. Mã PackageType, không phải ID
        String packageTypeCode

) {

    /**
     * Constructor cũ để code parser hiện tại
     * vẫn biên dịch trong lúc chuyển đổi.
     *
     * Chỉ dùng để tương thích tạm thời.
     */
    public PackageImportRow(
            int rowIndex,
            String packageCode,
            BigDecimal lengthCm,
            BigDecimal widthCm,
            BigDecimal heightCm,
            BigDecimal weightKg,
            String handlingClass,
            String destination
    ) {
        this(
                rowIndex,
                packageCode,
                null,
                lengthCm,
                widthCm,
                heightCm,
                weightKg,
                1,
                destination,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                handlingClass,
                null
        );
    }
}
