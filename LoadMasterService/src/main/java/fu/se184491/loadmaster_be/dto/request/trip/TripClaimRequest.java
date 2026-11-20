package fu.se184491.loadmaster_be.dto.request.trip;

import jakarta.validation.constraints.Size;

public record TripClaimRequest(
        @Size(max = 300, message = "Lời nhắn tối đa 300 ký tự")
        String note
) {}
