package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlacementResultDto {

    private Long packageId;
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
