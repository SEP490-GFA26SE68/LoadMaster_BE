package fu.se184491.loadmaster_be.dto.response.route;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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

    private String runId;

    private String candidateLabel;

    private String objective;

    private Boolean approved;

    private List<Long> orderedStops;

    private Integer deadlineMissedCount;

    private LocalDateTime createdAt;
}
