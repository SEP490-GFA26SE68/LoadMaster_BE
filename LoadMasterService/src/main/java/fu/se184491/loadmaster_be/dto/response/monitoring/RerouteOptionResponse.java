package fu.se184491.loadmaster_be.dto.response.monitoring;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RerouteOptionResponse {

    private Integer index;
    private String route;
    private Double distanceKm;
    private Integer durationMinutes;
    private Long extraMs;
    /** ISO-8601 */
    private String eta;
}
