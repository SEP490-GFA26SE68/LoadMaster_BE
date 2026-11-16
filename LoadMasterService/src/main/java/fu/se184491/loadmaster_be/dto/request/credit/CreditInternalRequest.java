package fu.se184491.loadmaster_be.dto.request.credit;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditInternalRequest {
    @JsonAlias("company_id")
    private Long companyId;

    @JsonAlias({"jobUuid", "job_uuid"})
    private String reference;
    @JsonAlias("execution_generation")
    private Long executionGeneration;
    public CreditInternalRequest(Long companyId, String reference) {
        this.companyId = companyId; this.reference = reference;
    }
}
