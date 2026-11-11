package fu.se184491.loadmaster_be.dto.response.support;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Kết quả mở khóa tài khoản dùng thử qua phiếu hỗ trợ.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnlockTrialResponse {

    /** ID người dùng vừa được mở khóa */
    private Long userId;

    /** Email người dùng vừa được mở khóa */
    private String userEmail;

    /**
     * true  = tài khoản vẫn còn trong giai đoạn dùng thử → mở lại thỏa thuận, cần ký trong 72h.
     * false = giai đoạn dùng thử đã hết → tài khoản ACTIVE nhưng bị giới hạn, chỉ được vào trang nâng cấp.
     */
    private boolean trialStillValid;

    /** Hành động tiếp theo FE cần thực hiện: "SIGN_AGREEMENT" hoặc "UPGRADE_PLAN" */
    private String nextAction;

    /** Mã phiếu hỗ trợ đã xử lý */
    private String ticketCode;

    /** Trạng thái phiếu sau khi mở khóa (luôn là RESOLVED) */
    private String ticketStatus;
}

