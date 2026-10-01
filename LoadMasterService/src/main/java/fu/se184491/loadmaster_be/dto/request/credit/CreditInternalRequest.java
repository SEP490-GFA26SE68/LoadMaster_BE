package fu.se184491.loadmaster_be.dto.request.credit;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditInternalRequest {
    private Long companyId;

    @JsonAlias({"jobUuid", "job_uuid"})
    private String reference;
}
