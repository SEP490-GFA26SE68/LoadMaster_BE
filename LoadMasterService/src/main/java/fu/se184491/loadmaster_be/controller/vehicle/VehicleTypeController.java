package fu.se184491.loadmaster_be.controller.vehicle;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.vehicle.CreateVehicleTypeRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleTypeResponse;
import fu.se184491.loadmaster_be.service.vehicle.VehicleTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/vehicle-types")
@RequiredArgsConstructor
public class VehicleTypeController {

    private final VehicleTypeService vehicleTypeService;
    
    // TODO: Extract companyId from Security Context
    private Long getCurrentCompanyId() {
        return 1L; 
    }

    @PostMapping
    @PreAuthorize("hasAuthority('VEHICLE_TYPES_CREATE')")
    public ResponseEntity<ApiResponse<VehicleTypeResponse>> createVehicleType(
            @Valid
            @RequestBody
            CreateVehicleTypeRequest request
    ) {
        VehicleTypeResponse response =
                vehicleTypeService.createVehicleType(request);

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
    @PreAuthorize("hasAuthority('VEHICLE_TYPES_READ')")
    public PageResponse<VehicleTypeResponse> getVehicleTypes(
            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "20")
            int size,

            @RequestParam(required = false)
            String search
    ) {
        return vehicleTypeService.getVehicleTypes(
                page,
                size,
                search
        );
    }
}
