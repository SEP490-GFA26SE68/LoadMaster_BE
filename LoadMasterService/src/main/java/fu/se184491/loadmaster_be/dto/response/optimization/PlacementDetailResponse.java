package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Một kiện đã xếp của phương án, đủ cho khung 3D.
 * Toạ độ và kích thước: cm (gốc ở góc vách trước - trái - sàn, X dọc thùng về phía cửa sau).
 * Khối lượng: kg. Kích thước đã áp hướng xoay.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlacementDetailResponse {
    private Long id;
    private Long packageId;
    private String packageCode;
    private String trackingBarcode;
    private BigDecimal weightKg;
    private BigDecimal posX;
    private BigDecimal posY;
    private BigDecimal posZ;
    private BigDecimal packedLength;
    private BigDecimal packedWidth;
    private BigDecimal packedHeight;
    private Integer rotationType;
    private Integer stepSequence;
    private Boolean pinned;
    private Long stopId;
    private String stopName;
    private Integer stopSequence;
    /**
     * Các mã hướng (0..5, như {@code rotationType}) kiện được nhận khi chỉnh tay, theo loại kiện như dịch vụ tối ưu đọc:
     * không có loại kiện hoặc tắt xoay thì chỉ hướng gốc. Luôn gồm hướng đang xếp.
     */
    private List<Integer> allowedRotations;
}
