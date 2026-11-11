package fu.se184491.loadmaster_be.dto.response.support;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.AssertTrue;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuestTrialInitResponse {

    private String token;
    private String email;
    private String maskedEmail;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expiresAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime otpExpiresAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime otpResendAllowedAt;

    private String message;

    @AssertTrue(message = "EXPIRES_BEFORE_CREATED")
    public boolean isSessionTimestampsValid() {
        if (createdAt == null) {
            return true;
        }
        if (expiresAt != null && expiresAt.isBefore(createdAt)) {
            return false;
        }
        if (otpExpiresAt != null && otpExpiresAt.isBefore(createdAt)) {
            return false;
        }
        if (otpResendAllowedAt != null && otpResendAllowedAt.isBefore(createdAt)) {
            return false;
        }
        return true;
    }
}

