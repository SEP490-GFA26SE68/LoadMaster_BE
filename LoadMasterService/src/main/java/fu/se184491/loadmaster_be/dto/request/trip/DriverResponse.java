package fu.se184491.loadmaster_be.dto.request.trip;

import lombok.Builder;

@Builder
public record DriverResponse(
        Long id,
        String fullName
) {

}
