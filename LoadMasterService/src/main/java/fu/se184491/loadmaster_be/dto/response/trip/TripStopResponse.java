
package fu.se184491.loadmaster_be.dto.response.trip;

import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record TripStopResponse(
        Long id,
        Long tripId,
        Long deliveryRequirementId,
        Integer stopSequence,
        String stopName,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        DeliveryStopStatus status,
        LocalDateTime plannedArrival,
        LocalDateTime actualArrival
) {

    public static TripStopResponse fromEntity(DeliveryStop stop) {
        return TripStopResponse.builder()
                .id(stop.getId())
                .tripId(
                        stop.getTrip() != null
                                ? stop.getTrip().getId()
                                : null
                )
                .deliveryRequirementId(
                        stop.getDeliveryRequirement() != null
                                ? stop.getDeliveryRequirement().getId()
                                : null
                )
                .stopSequence(stop.getStopSequence())
                .stopName(stop.getStopName())
                .address(stop.getAddress())
                .latitude(stop.getLatitude())
                .longitude(stop.getLongitude())
                .status(stop.getStatus())
                .plannedArrival(stop.getPlannedArrival())
                .actualArrival(stop.getActualArrival())
                .build();
    }
}
