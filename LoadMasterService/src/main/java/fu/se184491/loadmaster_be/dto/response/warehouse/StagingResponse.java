package fu.se184491.loadmaster_be.dto.response.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Kết quả đối chiếu một kiện khi soạn hàng. {@code alreadyStaged}: kiện đã soạn từ trước, máy chủ không ghi gì thêm. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StagingResponse {

    private Long tripId;
    private Long packageId;
    private boolean alreadyStaged;
    /** {@code QR}, {@code CODE} hoặc {@code MANUAL}. */
    private String method;
    private int stagedCount;
    private int totalCount;
    private boolean stagingComplete;
}
