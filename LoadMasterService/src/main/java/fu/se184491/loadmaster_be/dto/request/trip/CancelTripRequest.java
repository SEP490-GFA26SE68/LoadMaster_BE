package fu.se184491.loadmaster_be.dto.request.trip;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelTripRequest(

        @NotBlank(message = "Vui lòng nhập lý do huỷ chuyến")
        @Size(max = 500, message = "Lý do huỷ tối đa 500 ký tự")
        String reason

) {}
