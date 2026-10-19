package fu.se184491.loadmaster_be.dto.response.trip;

import lombok.Builder;

import java.util.List;

@Builder
public record TripDetailResponse(
        TripResponse trip,
        List<TripStopResponse> stops
) {
}