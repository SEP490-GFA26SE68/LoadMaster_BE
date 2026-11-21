package fu.se184491.loadmaster_be.dto.request.trip;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectTripClaimRequest(
        @NotBlank(message = "Lý do từ chối không được để trống")
        @Size(max = 300, message = "Lý do từ chối tối đa 300 ký tự")
        String reason
) {}
