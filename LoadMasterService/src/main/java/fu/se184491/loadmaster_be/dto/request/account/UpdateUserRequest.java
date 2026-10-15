package fu.se184491.loadmaster_be.dto.request.account;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import jakarta.validation.constraints.NotBlank;

public record UpdateUserRequest(
        @NotBlank
        String fullName,
        String phoneNumber,
        UserRole role
) {
}
