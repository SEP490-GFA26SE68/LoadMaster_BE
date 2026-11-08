package fu.se184491.loadmaster_be.dto.response.support;

import lombok.*;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuestTrialInitResponse {

    private String token;
    private String email;
    private String maskedEmail;
    private Instant otpExpiresAt;
    private Instant otpResendAllowedAt;
    private String message;
}

