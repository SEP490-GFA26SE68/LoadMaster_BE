package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.trip.DriverResponse;
import fu.se184491.loadmaster_be.dto.request.trip.TripCreateRequest;
import fu.se184491.loadmaster_be.dto.response.trip.TripDetailResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripStopResponse;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.sql.Driver;
import java.time.LocalDate;
import java.util.List;

public interface TripService {
    TripResponse createTrip(TripCreateRequest request);

    List<DriverResponse> getDrivers();

    TripResponse getTripById(Long tripId);

    Page<TripResponse> getTrips(
            Pageable pageable,
            String search,
            TripStatus status,
            LocalDate fromDate,
            LocalDate toDate,
            Long vehicleId,
            Long driverId,
            boolean unassignedDriver
    );

    TripDetailResponse getTripDetail(Long tripId);

    TripStopResponse assignRequirementToTrip(
            Long tripId,
            Long requirementId
    );
}
