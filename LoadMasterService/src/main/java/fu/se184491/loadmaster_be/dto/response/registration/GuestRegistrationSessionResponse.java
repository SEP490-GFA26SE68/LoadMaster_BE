package fu.se184491.loadmaster_be.dto.response.registration;

import fu.se184491.loadmaster_be.constant.registration.RegistrationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestRegistrationSessionResponse {

    private String token;
    private RegistrationStatus status;
    private String email;
    private String fullName;
    private String phoneNumber;
    private String companyName;
    private String companyCode;
    private String taxCode;
    private Long planId;
    private String planName;
    private String billingCycle;
    private LocalDateTime otpExpiresAt;
    private LocalDateTime expiresAt;
    private String paymentRef;
    private String message;
}
