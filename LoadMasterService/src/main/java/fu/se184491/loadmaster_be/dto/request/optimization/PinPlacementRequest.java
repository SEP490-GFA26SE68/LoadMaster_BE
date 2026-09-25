package fu.se184491.loadmaster_be.dto.request.optimization;

import jakarta.validation.constraints.NotNull;
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
public class PinPlacementRequest {

    @NotNull(message = "VALIDATION_ERROR")
    private Long placementId;
}
