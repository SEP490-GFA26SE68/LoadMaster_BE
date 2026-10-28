package fu.se184491.loadmaster_be.dto.request.company;

import jakarta.validation.constraints.AssertTrue;
import lombok.Builder;

@Builder
public record SignAgreementRequest(
        @AssertTrue(message = "Bạn cần xác nhận đồng ý với các điều khoản của thỏa thuận dịch vụ")
        Boolean confirmTerms,

        String signerName,
        String signerPhone
) {
}
