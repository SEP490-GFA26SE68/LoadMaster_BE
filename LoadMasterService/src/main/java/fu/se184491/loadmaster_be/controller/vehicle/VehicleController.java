package fu.se184491.loadmaster_be.controller.vehicle;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.vehicle.AssignDriverRequest;
import fu.se184491.loadmaster_be.dto.request.vehicle.CreateVehicleRequest;
import fu.se184491.loadmaster_be.dto.request.vehicle.UpdateVehicleRequest;
import fu.se184491.loadmaster_be.dto.request.vehicle.VehicleMaintenanceRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleDeleteResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleConfigResponse;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import fu.se184491.loadmaster_be.service.vehicle.VehicleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;

    // TODO: Extract companyId from Security Context
    private Long getCurrentCompanyId() {
        return 1L; 
    }

    @PostMapping
    @PreAuthorize("hasAuthority('VEHICLES_CREATE')")
    public ResponseEntity<ApiResponse<VehicleResponse>> createVehicle(
            @Valid
            @RequestBody
            CreateVehicleRequest request
    ) {
        VehicleResponse response =
                vehicleService.createVehicle(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        ApiResponse.success(
                                "Vehicle type created successfully",
                                response
                        )
                );
    }
    @GetMapping
    @PreAuthorize("hasAuthority('VEHICLES_READ')")
    public PageResponse<VehicleResponse> getVehicles(
            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "20")
            int size,

            @RequestParam(required = false)
            String search,

            @RequestParam(required = false)
            Long vehicleTypeId
    ) {
        return vehicleService.getVehicles(
                page,
                size,
                search,
                vehicleTypeId
        );
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VEHICLES_READ')")
    public ResponseEntity<ApiResponse<VehicleResponse>> getVehicleById(
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        vehicleService.getVehicleById(id)
                )
        );
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('VEHICLES_UPDATE')")
    public ResponseEntity<ApiResponse<VehicleResponse>> updateVehicle(
            @PathVariable Long id,
            @Valid @RequestBody UpdateVehicleRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(vehicleService.updateVehicle(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('VEHICLES_DELETE')")
    public ResponseEntity<ApiResponse<VehicleDeleteResponse>> deleteVehicle(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(vehicleService.deleteVehicle(id)));
    }

    @PostMapping("/{id}/maintenance")
    @PreAuthorize("hasAuthority('VEHICLES_UPDATE')")
    public ResponseEntity<ApiResponse<VehicleResponse>> startMaintenance(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) VehicleMaintenanceRequest request
    ) {
        String note = request == null ? null : request.note();
        return ResponseEntity.ok(ApiResponse.success(vehicleService.startMaintenance(id, note)));
    }

    @DeleteMapping("/{id}/maintenance")
    @PreAuthorize("hasAuthority('VEHICLES_UPDATE')")
    public ResponseEntity<ApiResponse<VehicleResponse>> endMaintenance(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(vehicleService.endMaintenance(id)));
    }

    @GetMapping("/{id}/config")
    @PreAuthorize("hasAuthority('VEHICLES_READ')")
    public ResponseEntity<ApiResponse<TripVehicleConfigResponse>> getVehicleConfig(
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        vehicleService.getVehicleConfig(id)
                )
        );
    }

}
