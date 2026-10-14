package fu.se184491.loadmaster_be.dto.response.route;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutePlanSummaryResponse {

    private Long id;

    private Long tripId;

    private Integer planVersion;

    private Integer totalDistanceMeters;

    private Integer totalDurationSeconds;

    private Boolean isActive;

    private LocalDateTime createdAt;
}
