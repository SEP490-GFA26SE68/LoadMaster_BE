
package fu.se184491.loadmaster_be.dto.request.trip;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record TripAssignRequirementsRequest(

        @NotEmpty(message = "Phải chọn ít nhất một yêu cầu giao")
        List<
                @NotNull(message = "ID yêu cầu giao không được null")
                @Positive(message = "ID yêu cầu giao phải lớn hơn 0")
                Long
        > requirementIds

) {
}
