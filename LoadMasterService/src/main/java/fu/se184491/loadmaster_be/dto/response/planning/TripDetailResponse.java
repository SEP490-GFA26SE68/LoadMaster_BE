package fu.se184491.loadmaster_be.dto.response.planning;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;

import java.time.LocalDateTime;
import java.util.List;

public record TripDetailResponse(
        Long id,
        String tripCode,
        Long vehicleId,
        Long driverId,
        LocalDateTime departureTime,
        TripStatus status,
        List<Long> packageIds,
        List<Stop> stops
) {
    public record Stop(
            Long id,
            int stopSequence,
            String name,
            String address,
            Double latitude,
            Double longitude,
            LocalDateTime plannedArrival,
            List<Long> packageIds
    ) {
    }
}
