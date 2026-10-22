package fu.se184491.loadmaster_be.dto.response.company;

import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyResponse {
    private Long id;
    private String companyCode;
    private String companyName;
    private String taxCode;
    private String billingEmail;
    private CompanyStatus status;
    private Instant createdAt;

    private String planName;
    private String planTier;
    private String planCode;
    private String subscriptionStatus;
    private Integer userCount;
    private String adminName;
    private String adminEmail;
    private String adminPhone;
    private Long adminId;
    private LocalDateTime subscriptionStartedAt;
    private LocalDateTime subscriptionExpiresAt;
    private Integer creditBalance;
}
