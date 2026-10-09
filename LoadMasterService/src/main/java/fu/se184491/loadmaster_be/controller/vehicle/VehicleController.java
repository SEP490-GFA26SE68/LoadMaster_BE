package fu.se184491.loadmaster_be.controller.vehicle;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.vehicle.AssignDriverRequest;
import fu.se184491.loadmaster_be.dto.request.vehicle.CreateVehicleRequest;
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

}
