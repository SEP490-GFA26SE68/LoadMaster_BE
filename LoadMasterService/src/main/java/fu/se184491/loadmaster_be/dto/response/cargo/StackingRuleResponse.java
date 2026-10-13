package fu.se184491.loadmaster_be.dto.response.cargo;

import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StackingRuleResponse {
    private Long id;
    private Long companyId;
    private Long bottomPackageTypeId;
    private String bottomPackageTypeName;
    private Long topPackageTypeId;
    private String topPackageTypeName;
    private Boolean isAllowed;
    private BigDecimal minContactRatio;
}
