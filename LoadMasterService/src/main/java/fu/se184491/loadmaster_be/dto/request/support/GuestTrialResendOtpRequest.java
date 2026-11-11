package fu.se184491.loadmaster_be.dto.request.support;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuestTrialResendOtpRequest {

    @NotBlank(message = "Token không được để trống")
    private String token;
}

