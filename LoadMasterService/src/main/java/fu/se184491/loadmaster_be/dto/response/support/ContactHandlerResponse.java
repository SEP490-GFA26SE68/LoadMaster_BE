package fu.se184491.loadmaster_be.dto.response.support;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import lombok.*;

/** Một người nhận việc được của yêu cầu liên hệ. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContactHandlerResponse {
    private Long id;
    private String fullName;
    private String email;
    private UserRole role;
}
