package fu.se184491.loadmaster_be.dto.request.planning;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record AddPackagesToTripRequest(
        @NotEmpty List<@NotNull Long> packageIds,
        boolean override,
        String overrideReason
) {
}
