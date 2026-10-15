package fu.se184491.loadmaster_be.dto.request.subscription;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateContractRequest {

    @NotNull(message = "Mã công ty không được để trống")
    private Long companyId;

    @NotNull(message = "Mã gói cước không được để trống")
    private Long planId;

    @Builder.Default
    private Boolean autoRenew = true;

    private String billingPeriod;
}
