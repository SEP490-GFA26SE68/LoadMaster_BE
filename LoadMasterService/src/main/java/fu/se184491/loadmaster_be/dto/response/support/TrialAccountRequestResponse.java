package fu.se184491.loadmaster_be.dto.response.support;

import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import lombok.*;

import java.time.Instant;

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
    private Long interestedPlanId;

    private String interestedPlanName;

    private TrialRequestStatus status;

    private String note;

    private Long requestedById;

    private String requestedByName;

    private Long approvedById;

    private String approvedByName;

    private Long createdCompanyId;

    private String createdCompanyName;

    private Instant createdAt;

    private Instant resolvedAt;
}
