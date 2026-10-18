
package fu.se184491.loadmaster_be.dto.request.planning;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateRequirementCoordinatesRequest(

        @NotNull
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        BigDecimal destinationLat,

        @NotNull
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        BigDecimal destinationLng

) {}
