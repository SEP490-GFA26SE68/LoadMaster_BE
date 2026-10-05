package fu.se184491.loadmaster_be.dto.request.account;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateUserRequest(

        @NotBlank
        @Email
        String email,


        @NotBlank
        String fullName,

        String phoneNumber,
        Long companyId,

        @NotNull
        UserRole role
) {
}