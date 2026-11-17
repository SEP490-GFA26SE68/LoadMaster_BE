package fu.se184491.loadmaster_be.dto.request.warehouse;

import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ConfirmPlacementRequest {
    @NotNull private Long tripId;
    @NotNull private StagingMethod method;
    @NotBlank private String code;
}
