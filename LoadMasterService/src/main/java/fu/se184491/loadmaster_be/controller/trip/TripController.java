
package fu.se184491.loadmaster_be.controller.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.trip.DriverResponse;
import fu.se184491.loadmaster_be.dto.request.trip.TripCreateRequest;
import fu.se184491.loadmaster_be.dto.response.trip.TripDetailResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripResponse;
import fu.se184491.loadmaster_be.service.trip.TripService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    /**
     * Tạo chuyến mới.
     * Chuyến được tạo với trạng thái DRAFT.
     */
    @PostMapping
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ApiResponse<TripResponse>> createTrip(
            @Valid @RequestBody TripCreateRequest request
    ) {
        TripResponse response = tripService.createTrip(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @GetMapping("/driver-options")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ApiResponse<List<DriverResponse>>> getDrivers(
    ) {
        List<DriverResponse> response = tripService.getDrivers();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ApiResponse<TripResponse>> getTripById(
            @PathVariable Long id
    ) {
        TripResponse response = tripService.getTripById(id);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<PageResponse<TripResponse>> getTrips(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,

            @RequestParam(required = false) String search,
            @RequestParam(required = false) TripStatus status,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fromDate,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate toDate,

            @RequestParam(required = false) Long vehicleId,
            @RequestParam(required = false) Long driverId,
            @RequestParam(defaultValue = "false") boolean unassignedDriver,

            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "page must be >= 0 and size must be between 1 and 100"
            );
        }

        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "fromDate must not be after toDate"
            );
        }

        if (driverId != null && unassignedDriver) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "driverId and unassignedDriver cannot be used together"
            );
        }

        // Whitelist: không cho client sắp xếp theo thuộc tính JPA tùy ý.
        Set<String> allowedSorts = Set.of(
                "createdAt",
                "departureTime",
                "tripName",
                "tripCode",
                "status"
        );

        if (!allowedSorts.contains(sortBy)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported sortBy"
            );
        }

        Sort.Direction direction;

        try {
            direction = Sort.Direction.fromString(sortDir);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "sortDir must be asc or desc"
            );
        }

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        new Sort.Order(direction, sortBy),
                        Sort.Order.desc("id")
                )
        );

        Page<TripResponse> result = tripService.getTrips(
                pageable,
                search,
                status,
                fromDate,
                toDate,
                vehicleId,
                driverId,
                unassignedDriver
        );

        return ResponseEntity.ok(PageResponse.of(result));
    }


    @GetMapping("/{id}/detail")
    public ApiResponse<TripDetailResponse> getTripDetail(
            @PathVariable Long id
    ) {
        TripDetailResponse response = tripService.getTripDetail(id);

        return ApiResponse.success(
                "Lấy chi tiết chuyến hàng thành công",
                response
        );
    }

}
