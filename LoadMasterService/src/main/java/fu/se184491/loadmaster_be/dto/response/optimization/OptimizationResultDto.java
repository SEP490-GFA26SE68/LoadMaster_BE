package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptimizationResultDto {

    private Integer packedItemsCount;
    private BigDecimal volumeUtilization;
    private BigDecimal weightUtilization;
    private Long computationMs;
    /** Thuật toán đã tạo ra phương án (EP_DBLF | GA | SA | EP_LUMO_R1_1 | LUMO_R1…); dịch vụ cũ không gửi: null. */
    private String algorithmName;
    private List<PlacementResultDto> placements;
}
