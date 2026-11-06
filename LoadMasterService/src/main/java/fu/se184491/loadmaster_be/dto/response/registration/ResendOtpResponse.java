package fu.se184491.loadmaster_be.dto.response.registration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResendOtpResponse {
    private String message;
    private LocalDateTime otpExpiresAt;
    private LocalDateTime otpResendAllowedAt;
}
