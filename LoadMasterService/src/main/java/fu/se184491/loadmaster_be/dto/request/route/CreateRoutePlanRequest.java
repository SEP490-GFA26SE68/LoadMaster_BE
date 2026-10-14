package fu.se184491.loadmaster_be.dto.request.route;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRoutePlanRequest {

    private Integer planVersion;

    private Integer totalDistanceMeters;

    private Integer totalDurationSeconds;

    @NotEmpty(message = "Danh sách chặng đường không được để trống")
    private List<@Valid CreateRouteLegRequest> legs;
}
