package fu.se184491.loadmaster_be.dto.request.route;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRouteSuggestionRequest {

    private Long tripExceptionId;

    @NotBlank(message = "Loại đề xuất không được để trống")
    private String suggestionType;

    private Integer durationDiffSeconds;

    private Integer distanceDiffMeters;

    private String proposedPolyline;
}
