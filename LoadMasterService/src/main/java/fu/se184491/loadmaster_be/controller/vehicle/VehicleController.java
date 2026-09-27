package fu.se184491.loadmaster_be.controller.vehicle;

import fu.se184491.loadmaster_be.dto.request.vehicle.VehicleRequest;
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
    @PreAuthorize("hasAuthority('VEHICLE_MANAGE')")
    public ResponseEntity<VehicleResponse> createVehicle(@Valid @RequestBody VehicleRequest request) {
        return new ResponseEntity<>(vehicleService.createVehicle(getCurrentCompanyId(), request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('VEHICLE_MANAGE')")
    public ResponseEntity<VehicleResponse> updateVehicle(
            @PathVariable Long id,
            @Valid @RequestBody VehicleRequest request) {
        return ResponseEntity.ok(vehicleService.updateVehicle(getCurrentCompanyId(), id, request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VEHICLE_MANAGE')")
    public ResponseEntity<VehicleResponse> getVehicleById(@PathVariable Long id) {
        return ResponseEntity.ok(vehicleService.getVehicleById(getCurrentCompanyId(), id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VEHICLE_MANAGE')")
    public ResponseEntity<Page<VehicleResponse>> getAllVehicles(
            @RequestParam(required = false) Long vehicleTypeId,
            @RequestParam(required = false) Long driverUserId,
            Pageable pageable) {
        return ResponseEntity.ok(vehicleService.getAllVehicles(getCurrentCompanyId(), vehicleTypeId, driverUserId, pageable));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('VEHICLE_MANAGE')")
    public ResponseEntity<Void> deleteVehicle(@PathVariable Long id) {
        vehicleService.deleteVehicle(getCurrentCompanyId(), id);
        return ResponseEntity.noContent().build();
    }
}
