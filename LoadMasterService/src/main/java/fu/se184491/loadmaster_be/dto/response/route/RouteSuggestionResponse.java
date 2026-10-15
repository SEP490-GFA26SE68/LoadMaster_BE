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
public class RouteSuggestionResponse {

    private Long id;

    private Long routeLegId;

    private Long tripExceptionId;

    private String suggestionType;

    private Integer durationDiffSeconds;

    private Integer distanceDiffMeters;

    private Boolean isAccepted;

    private String proposedPolyline;

    private LocalDateTime createdAt;
}
