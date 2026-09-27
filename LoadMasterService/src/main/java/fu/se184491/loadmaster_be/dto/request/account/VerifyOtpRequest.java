package fu.se184491.loadmaster_be.dto.request.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record VerifyOtpRequest(
        @Email @NotBlank String email,
        @NotBlank String otp
) {}