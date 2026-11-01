package fu.se184491.loadmaster_be.dto.response.route;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteGuidanceStepResponse {

    private String instruction;

    private String maneuver;

    private Integer distanceMeters;

    private Integer durationSeconds;

    private Double lat;

    private Double lng;
}
