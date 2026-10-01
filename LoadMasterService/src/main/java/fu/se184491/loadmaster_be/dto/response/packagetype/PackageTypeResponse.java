package fu.se184491.loadmaster_be.dto.response.packagetype;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PackageTypeResponse {
    private Long id;
    private Long companyId;
    private String name;
    private Integer length;
    private Integer width;
    private Integer height;
    private BigDecimal weightKg;
    private BigDecimal maxStackingWeightKg;
    private Boolean isFragile;
}
