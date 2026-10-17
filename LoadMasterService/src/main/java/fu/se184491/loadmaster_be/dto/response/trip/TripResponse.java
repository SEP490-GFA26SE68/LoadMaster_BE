package fu.se184491.loadmaster_be.dto.response.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Builder
public record TripResponse(
        Long id,
        String tripCode,
        String tripName,

        Long companyId,

        Long vehicleId,
        String vehicleName,
        String licensePlate,

        Long driverId,
        String driverName,

        Long createdByDispatcherId,

        LocalDateTime departureTime,

        String originName,
        String originAddress,
        BigDecimal originLatitude,
        BigDecimal originLongitude,

        TripStatus status,
        Instant createdAt
) {

    public static TripResponse fromEntity(Trip trip) {
        return new TripResponse(
                trip.getId(),
                trip.getTripCode(),
                trip.getTripName(),

                trip.getCompany() != null
                        ? trip.getCompany().getId()
                        : null,

                trip.getVehicle().getId(),
                trip.getVehicle().getName(),
                trip.getVehicle().getLicensePlate(),

                trip.getDriver() != null
                        ? trip.getDriver().getId()
                        : null,

                trip.getDriver() != null
                        ? trip.getDriver().getFullName()
                        : null,

                trip.getCreatedByDispatcher().getId(),

                trip.getDepartureTime(),

                trip.getOriginName(),
                trip.getOriginAddress(),
                trip.getOriginLatitude(),
                trip.getOriginLongitude(),

                trip.getStatus(),
                trip.getCreatedAt()
        );
    }
}
