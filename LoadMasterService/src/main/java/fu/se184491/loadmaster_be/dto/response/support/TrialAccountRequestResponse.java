package fu.se184491.loadmaster_be.dto.response.support;

import com.fasterxml.jackson.annotation.JsonFormat;
import fu.se184491.loadmaster_be.constant.support.AiPrecheckStatus;
import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import jakarta.validation.constraints.AssertTrue;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrialAccountRequestResponse {

    private Long id;

    private Long contactId;

    private String fullName;

    private String email;

    private String phoneNumber;

    private String companyName;
    private String companyCode;
    private String taxCode;
    private String companyAddress;

    private Long interestedPlanId;
    private String interestedPlanName;

    private TrialRequestStatus status;
    private AiPrecheckStatus aiPrecheckStatus;
    private String aiPrecheckReason;
    private String handledByName;
    private String rejectionReason;

    private String note;
    private String managerNote;
    private Integer trialDays;
    private Integer trialCredits;

    private Long requestedById;
    private String requestedByName;

    private Long managerReviewedById;
    private String managerReviewedByName;
    private String managerReviewedByEmail;

    private Long approvedById;
    private String approvedByName;
    private String approvedByEmail;

    private Long createdCompanyId;
    private String createdCompanyName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime resolvedAt;

    @AssertTrue(message = "RESOLVED_BEFORE_CREATED")
    public boolean isResolvedAtAfterCreatedAt() {
        return createdAt == null || resolvedAt == null || !resolvedAt.isBefore(createdAt);
    }
}
