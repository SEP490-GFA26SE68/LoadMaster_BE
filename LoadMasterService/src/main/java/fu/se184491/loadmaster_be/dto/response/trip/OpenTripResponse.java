package fu.se184491.loadmaster_be.dto.response.trip;

import fu.se184491.loadmaster_be.constant.trip.TripClaimStatus;

import java.time.LocalDateTime;

public record OpenTripResponse(
        Long id,
        Long tripId,
        String tripCode,
        String tripName,
        LocalDateTime departureTime,
        String originWarehouse,
        Integer stopCount,
        TripClaimStatus myClaimStatus,
        String myRejectReason
) {}
