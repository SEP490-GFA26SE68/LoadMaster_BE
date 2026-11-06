package fu.se184491.loadmaster_be.dto.response.driver;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Một xác nhận tay khi dỡ: kiện, điểm giao, người gửi, lý do, trạng thái duyệt. Mốc giờ: {@code LocalDateTime} giờ Việt Nam. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManualConfirmationResponse {

    private Long id;
    private Long tripId;
    private String tripCode;
    private Long stopId;
    private Integer stopSequence;
    private String stopName;
    private Long packageId;
    private String packageCode;
    private String trackingBarcode;
    private String packageName;
    /** {@code LABEL_DAMAGED}, {@code QR_UNREADABLE} hoặc {@code OTHER}. */
    private String reason;
    private String note;
    /** {@code PENDING}, {@code APPROVED} hoặc {@code REJECTED}. */
    private String status;
    private Long requestedById;
    private String requestedByName;
    private LocalDateTime requestedAt;
    private String decidedByName;
    private LocalDateTime decidedAt;
    private String decisionNote;
}
