package fu.se184491.loadmaster_be.dto.request.support;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApproveTrialAccountRequest {

    private String companyCode;

    private String companyName;

    private String taxCode;

    private Long planId;

    private String note;
}
