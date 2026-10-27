
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
        Instant createdAt,

        Integer stopCount,
        Integer packageCount,
        Boolean hasActiveRoutePlan
) {

    // Dùng cho các API hiện tại.
    // Số liệu tổng hợp sẽ được bổ sung tại Service.
    public static TripResponse fromEntity(Trip trip) {
        return fromEntity(trip, 0, 0, false);
    }

    // Dùng khi trả danh sách chuyến có dữ liệu tổng hợp.
    public static TripResponse fromEntity(
            Trip trip,
            int stopCount,
            int packageCount,
            boolean hasActiveRoutePlan
    ) {
        return TripResponse.builder()
                .id(trip.getId())
                .tripCode(trip.getTripCode())
                .tripName(trip.getTripName())

                .companyId(
                        trip.getCompany() != null
                                ? trip.getCompany().getId()
                                : null
                )

                .vehicleId(trip.getVehicle().getId())
                .vehicleName(trip.getVehicle().getName())
                .licensePlate(trip.getVehicle().getLicensePlate())

                .driverId(
                        trip.getDriver() != null
                                ? trip.getDriver().getId()
                                : null
                )
                .driverName(
                        trip.getDriver() != null
                                ? trip.getDriver().getFullName()
                                : null
                )

                .createdByDispatcherId(
                        trip.getCreatedByDispatcher().getId()
                )

                .departureTime(trip.getDepartureTime())

                .originName(trip.getOriginName())
                .originAddress(trip.getOriginAddress())
                .originLatitude(trip.getOriginLatitude())
                .originLongitude(trip.getOriginLongitude())

                .status(trip.getStatus())
                .createdAt(trip.getCreatedAt())

                .stopCount(stopCount)
                .packageCount(packageCount)
                .hasActiveRoutePlan(hasActiveRoutePlan)

                .build();
    }
}
