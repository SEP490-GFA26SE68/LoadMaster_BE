package fu.se184491.loadmaster_be.dto.request.account;

import fu.se184491.loadmaster_be.constant.account.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(
        @NotNull
        UserStatus status
) {
}
