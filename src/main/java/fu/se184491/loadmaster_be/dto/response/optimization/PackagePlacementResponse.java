package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackagePlacementResponse {

    private Long id;
    private Long cargoPackageId;
    private Integer posX;
    private Integer posY;
    private Integer posZ;
    private Integer packedLength;
    private Integer packedWidth;
    private Integer packedHeight;
    private Integer rotationType;
    private Integer stepSequence;
    private Boolean pinned;
}
