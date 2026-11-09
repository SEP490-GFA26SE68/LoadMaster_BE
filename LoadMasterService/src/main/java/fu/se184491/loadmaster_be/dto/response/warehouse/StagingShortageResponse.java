package fu.se184491.loadmaster_be.dto.response.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Một kiện kho báo thiếu lúc soạn hàng. Mốc giờ: {@code LocalDateTime} giờ Việt Nam. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StagingShortageResponse {

    private Long id;
    private Long tripId;
    private String tripCode;
    private Long packageId;
    private String packageCode;
    private String trackingBarcode;
    private String packageName;
    private Long stopId;
    private Integer stopSequence;
    private String stopName;
    /** {@code OPEN}, {@code KEEP_SEARCHING}, {@code FOUND} hoặc {@code DROPPED}. */
    private String status;
    private String note;
    private String reportedByName;
    private LocalDateTime reportedAt;
    private String resolvedByName;
    private LocalDateTime resolvedAt;
    /** Trạng thái chuyến sau lần ghi ({@code PLANNED} khi kiện vừa bị bỏ khỏi chuyến). */
    private String tripStatus;
}
