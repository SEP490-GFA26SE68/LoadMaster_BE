
package fu.se184491.loadmaster_be.controller.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.trip.DriverResponse;
import fu.se184491.loadmaster_be.dto.request.trip.CancelTripRequest;
import fu.se184491.loadmaster_be.dto.request.trip.TripCreateRequest;
import fu.se184491.loadmaster_be.dto.request.trip.TripUpdateRequest;
import fu.se184491.loadmaster_be.dto.response.trip.TripDetailResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripStopResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleConfigResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleChoicesResponse;
import fu.se184491.loadmaster_be.service.trip.TripLifecycleService;
import fu.se184491.loadmaster_be.service.trip.TripService;
import fu.se184491.loadmaster_be.service.trip.TripVehicleChoiceService;

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
    private final TripLifecycleService tripLifecycleService;
    private final TripVehicleChoiceService tripVehicleChoiceService;

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
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    public ResponseEntity<ApiResponse<TripResponse>> getTripById(
            @PathVariable Long id
    ) {
        TripResponse response = tripService.getTripById(id);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'WAREHOUSE_WORKER', 'MANAGER')")
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
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    public ApiResponse<TripDetailResponse> getTripDetail(
            @PathVariable Long id
    ) {
        TripDetailResponse response = tripService.getTripDetail(id);

        return ApiResponse.success(
                "Lấy chi tiết chuyến hàng thành công",
                response
        );
    }


    /**
     * Dispatcher gán toàn bộ yêu cầu giao vào một chuyến.
     *
     * Backend kiểm tra điều kiện và tự sinh DeliveryStop.
     * Không chọn từng kiện thủ công.
     */
    @PostMapping("/{tripId}/delivery-requirements/{requirementId}")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ApiResponse<TripStopResponse>> assignRequirementToTrip(
            @PathVariable Long tripId,
            @PathVariable Long requirementId
    ) {
        TripStopResponse response =
                tripService.assignRequirementToTrip(
                        tripId,
                        requirementId
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Gán yêu cầu giao vào chuyến thành công",
                        response
                ));
    }

    /**
     * Các xe của công ty để đổi xe cho chuyến: cấu hình, trạng thái, chuyến đang dùng xe và lý do không chở được hàng.
     */
    @GetMapping("/{id}/vehicle-choices")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ApiResponse<TripVehicleChoicesResponse>> getVehicleChoices(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách xe cho chuyến hàng thành công", tripVehicleChoiceService.choices(id)));
    }

    /**
     * Lấy cấu hình xe được gán cho chuyến hàng.
     * Bao gồm thông tin xe, loại xe, kích thước và vật cản.
     */
    @GetMapping("/{id}/vehicle-config")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    public ResponseEntity<ApiResponse<TripVehicleConfigResponse>> getTripVehicleConfig(
            @PathVariable Long id
    ) {
        TripVehicleConfigResponse response =
                tripService.getTripVehicleConfig(id);

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Lấy cấu hình xe của chuyến hàng thành công",
                        response
                )
        );
    }


    /**
     * Sửa tên, giờ xuất phát, xe, tài xế, kho xuất phát của chuyến Nháp / Đã lập kế hoạch.
     * Đổi giờ xuất phát hoặc kho xuất phát thì bỏ tuyến, chuyến về Nháp.
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ApiResponse<TripResponse>> updateTrip(
            @PathVariable Long id,
            @Valid @RequestBody TripUpdateRequest request
    ) {
        tripLifecycleService.updateTrip(id, request);
        return ResponseEntity.ok(ApiResponse.success(
                "Cập nhật chuyến thành công",
                tripService.getTripById(id)
        ));
    }

    /**
     * Huỷ chuyến Nháp / Đã lập kế hoạch / Đang xếp hàng kèm lý do.
     * Yêu cầu giao và kiện của chuyến về Chờ xử lý.
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ApiResponse<TripResponse>> cancelTrip(
            @PathVariable Long id,
            @Valid @RequestBody CancelTripRequest request
    ) {
        tripLifecycleService.cancelTrip(id, request.reason());
        return ResponseEntity.ok(ApiResponse.success(
                "Huỷ chuyến thành công",
                tripService.getTripById(id)
        ));
    }

    /**
     * Gỡ một yêu cầu giao khỏi chuyến Nháp / Đã lập kế hoạch: xoá điểm giao của nó,
     * yêu cầu và kiện về Chờ xử lý, tuyến bị bỏ (chuyến về Nháp).
     */
    @DeleteMapping("/{tripId}/delivery-requirements/{requirementId}")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ApiResponse<TripDetailResponse>> removeRequirementFromTrip(
            @PathVariable Long tripId,
            @PathVariable Long requirementId
    ) {
        tripLifecycleService.removeRequirementFromTrip(tripId, requirementId);
        return ResponseEntity.ok(ApiResponse.success(
                "Gỡ yêu cầu giao khỏi chuyến thành công",
                tripService.getTripDetail(tripId)
        ));
    }
}
