package fu.se184491.loadmaster_be.controller.account;


import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.account.ForgotPasswordRequest;
import fu.se184491.loadmaster_be.dto.request.account.ResetPasswordRequest;
import fu.se184491.loadmaster_be.dto.request.account.VerifyOtpRequest;
import fu.se184491.loadmaster_be.service.security.PasswordResetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth/password-reset")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @PostMapping("/request")
    public ApiResponse<Void> requestOtp(
            @Valid @RequestBody ForgotPasswordRequest request
    ) {

        passwordResetService.requestOtp(
                request.email()
        );

        return ApiResponse.success(
                "Nếu email tồn tại, mã xác thực sẽ được gửi."
        );
    }

    @PostMapping("/verify")
    public ApiResponse<Map<String, String>> verifyOtp(
            @Valid @RequestBody VerifyOtpRequest request
    ) {

        String resetToken =
                passwordResetService.verifyOtp(
                        request.email(),
                        request.otp()
                );

        return ApiResponse.success(
                Map.of(
                        "resetToken",
                        resetToken
                )
        );
    }

    @PostMapping("/reset")
    public ApiResponse<Void> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request
    ) {

        passwordResetService.resetPassword(
                request.resetToken(),
                request.newPassword()
        );

        return ApiResponse.success(
                "Password reset successfully"
        );
    }
}