package fu.se184491.loadmaster_be.dto.response.route;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteLegResponse {

    private Long id;

    private Long routePlanId;

    private Integer legSequence;

    private Long startStopId;

    private Long endStopId;

    private Integer distanceMeters;

    private Integer durationSeconds;

    private String polyline;

    private List<RouteGuidanceStepResponse> steps;
}
