package fu.se184491.loadmaster_be.dto.request.cargo;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StackingRuleRequest {

    @NotNull(message = "Bottom Package Type ID is required")
    private Long bottomPackageTypeId;

    @NotNull(message = "Top Package Type ID is required")
    private Long topPackageTypeId;

    private Boolean isAllowed;

    @DecimalMin(value = "0.0", message = "Min contact ratio must be at least 0.0")
    @DecimalMax(value = "1.0", message = "Min contact ratio must be at most 1.0")
    private BigDecimal minContactRatio;
}
