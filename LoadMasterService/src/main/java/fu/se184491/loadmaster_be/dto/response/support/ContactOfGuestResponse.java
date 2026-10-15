package fu.se184491.loadmaster_be.dto.response.support;

import fu.se184491.loadmaster_be.constant.support.ContactRequestType;
import fu.se184491.loadmaster_be.constant.support.ContactStatus;
import lombok.*;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContactOfGuestResponse {

    private Long id;

    private String fullName;

    private String email;

    private String phoneNumber;

    private String companyName;

    private Long interestedPlanId;

    private String interestedPlanName;

    private Long assignedToId;

    private String assignedToName;

    private String message;

    private ContactRequestType requestType;

    private ContactStatus status;

    private String note;

    private Instant createdAt;

    private Instant resolvedAt;
}
