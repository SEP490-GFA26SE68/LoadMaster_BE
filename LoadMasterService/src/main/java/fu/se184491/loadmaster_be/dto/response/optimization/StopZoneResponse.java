package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một vùng theo điểm giao trong lòng thùng của phương án, dọc trục X (cm, gốc ở vách trước). Điểm giao trước nằm sát cửa sau.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StopZoneResponse {
    /** Mã vùng ("ZONE-" + mã điểm giao); kiện mang mã này ở {@code stopZoneId}. */
    private String id;
    private Long stopId;
    private Integer stopSequence;
    private Double startXCm;
    private Double endXCm;
    /** Tỷ lệ thể tích hàng của điểm giao trên tổng hàng của lần tối ưu, 0..1. */
    private Double volumeRatio;
}
