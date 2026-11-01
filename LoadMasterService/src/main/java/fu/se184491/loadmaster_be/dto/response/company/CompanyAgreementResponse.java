package fu.se184491.loadmaster_be.dto.response.company;

import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementType;
import lombok.Builder;

import java.time.Instant;
import java.time.LocalDateTime;

@Builder
public record CompanyAgreementResponse(
        Long id,
        Long companyId,
        String companyName,
        String companyCode,
        String taxCode,
        Long userId,
        AgreementType agreementType,
        String agreementCode,
        String title,
        String content,
        AgreementStatus status,
        LocalDateTime deadlineAt,
        LocalDateTime signedAt,
        String signerName,
        String signerEmail,
        String signerPhone,
        String ipAddress,
        boolean isExpired,
        long hoursRemaining,
        Instant createdAt
) {
}
