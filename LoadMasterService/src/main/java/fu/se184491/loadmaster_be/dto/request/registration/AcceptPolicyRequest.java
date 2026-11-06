package fu.se184491.loadmaster_be.dto.request.registration;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcceptPolicyRequest {

    @NotBlank(message = "Token phiên đăng ký không được để trống")
    private String token;
}

