package fu.se184491.loadmaster_be.dto.response.company;

import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import lombok.*;

import java.time.Instant;

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
}
