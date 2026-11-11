package fu.se184491.loadmaster_be.dto.response.support;

import fu.se184491.loadmaster_be.constant.support.AiPrecheckStatus;
import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuestTrialVerifyResponse {

    private Long requestId;
    private TrialRequestStatus status;
    private AiPrecheckStatus aiPrecheckStatus;
    private String aiPrecheckReason;
    private String message;
}

