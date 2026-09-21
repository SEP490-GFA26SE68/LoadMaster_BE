package fu.se184491.loadmaster_be.controller.vehicle;

import fu.se184491.loadmaster_be.dto.request.vehicle.VehicleTypeRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleTypeResponse;
import fu.se184491.loadmaster_be.service.vehicle.VehicleTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    @PreAuthorize("hasAuthority('VEHICLE_TYPE_MANAGE')")
    public ResponseEntity<VehicleTypeResponse> createVehicleType(@RequestBody @Valid VehicleTypeRequest request) {
        VehicleTypeResponse response = vehicleTypeService.createVehicleType(getCurrentCompanyId(), request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('VEHICLE_TYPE_MANAGE')")
    public ResponseEntity<VehicleTypeResponse> updateVehicleType(
            @PathVariable Long id, 
            @RequestBody @Valid VehicleTypeRequest request) {
        VehicleTypeResponse response = vehicleTypeService.updateVehicleType(getCurrentCompanyId(), id, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VEHICLE_TYPE_MANAGE')")
    public ResponseEntity<VehicleTypeResponse> getVehicleTypeById(@PathVariable Long id) {
        VehicleTypeResponse response = vehicleTypeService.getVehicleTypeById(getCurrentCompanyId(), id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VEHICLE_TYPE_MANAGE')")
    public ResponseEntity<Page<VehicleTypeResponse>> getAllVehicleTypes(Pageable pageable) {
        Page<VehicleTypeResponse> responses = vehicleTypeService.getAllVehicleTypes(getCurrentCompanyId(), pageable);
        return ResponseEntity.ok(responses);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('VEHICLE_TYPE_MANAGE')")
    public ResponseEntity<Void> deleteVehicleType(@PathVariable Long id) {
        vehicleTypeService.deleteVehicleType(getCurrentCompanyId(), id);
        return ResponseEntity.noContent().build();
    }
}
