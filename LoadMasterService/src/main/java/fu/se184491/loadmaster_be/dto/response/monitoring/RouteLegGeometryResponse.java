package fu.se184491.loadmaster_be.dto.response.monitoring;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteLegGeometryResponse {

    private Long legId;
    private Integer legSequence;
    private Long startStopId;
    private Long endStopId;
    private Integer distanceMeters;
    private Integer durationSeconds;
    private String polyline;
}
