package fu.se184491.loadmaster_be.dto.request.support;

import fu.se184491.loadmaster_be.constant.support.ContactStatus;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateContactOfGuestRequest {

    private ContactStatus status;

    private Long assignedToId;

    /** {@code true}: bỏ người nhận việc ({@code assignedToId: null} không đổi gì, như trước). */
    private Boolean unassign;

    private String note;
}
