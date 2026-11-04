package fu.se184491.loadmaster_be.dto.request.company;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import lombok.Builder;

@Builder
public record SignAgreementRequest(
        @AssertTrue(message = "Bạn cần xác nhận đồng ý với các điều khoản của thỏa thuận dịch vụ")
        Boolean confirmTerms,

        String signerName,
        String signerPhone,

        @Email(message = "Email người ký không hợp lệ")
        String signerEmail
) {
}
