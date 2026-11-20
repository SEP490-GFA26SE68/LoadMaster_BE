package fu.se184491.loadmaster_be.controller.vehicle;

import fu.se184491.loadmaster_be.dto.*;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import fu.se184491.loadmaster_be.service.vehicle.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor
public class VehiclePresenceController {
    private final VehiclePresenceService presence;
    private final VehicleDepartureEligibility eligibility;
    public record CheckIn(@NotNull @Positive Long vehicleId) {}
    @GetMapping("/api/vehicles/departure-choices") @PreAuthorize("hasRole('DISPATCHER')")
    public PageResponse<VehicleResponse> choices(@RequestParam long warehouseId,@RequestParam(required=false) Long tripId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size) {
        return PageResponse.of(eligibility.choices(warehouseId,tripId,page,size));
    }
    @GetMapping("/api/warehouse/vehicle-presence/warehouses") @PreAuthorize("hasRole('WAREHOUSE_WORKER')")
    public ApiResponse<List<Map<String,Object>>> warehouses() {return ApiResponse.success(presence.warehouses());}
    @GetMapping("/api/warehouse/vehicle-presence/{warehouseId}/search") @PreAuthorize("hasRole('WAREHOUSE_WORKER')")
    public ApiResponse<List<Map<String,Object>>> search(@PathVariable long warehouseId,@RequestParam String query) {return ApiResponse.success(presence.search(warehouseId,query));}
    @GetMapping("/api/warehouse/vehicle-presence/{warehouseId}/vehicles") @PreAuthorize("hasRole('WAREHOUSE_WORKER')")
    public PageResponse<Map<String,Object>> vehicles(@PathVariable long warehouseId,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="false") boolean atWarehouse) {return PageResponse.of(presence.vehicles(warehouseId,query,page,size,atWarehouse));}
    @PostMapping("/api/warehouse/vehicle-presence/{warehouseId}/check-in") @PreAuthorize("hasRole('WAREHOUSE_WORKER')")
    public ApiResponse<VehiclePresenceStore.Presence> checkIn(@PathVariable long warehouseId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody CheckIn body) {return ApiResponse.success(presence.checkIn(warehouseId,body.vehicleId(),key));}
    @PostMapping("/api/warehouse/vehicle-presence/{warehouseId}/loading-trips/{tripId}/confirm-presence") @PreAuthorize("hasRole('WAREHOUSE_WORKER')")
    public ApiResponse<VehiclePresenceStore.Presence> confirmLoading(@PathVariable long warehouseId,@PathVariable long tripId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody CheckIn body) {return ApiResponse.success(presence.confirmLoadingPresence(warehouseId,tripId,body.vehicleId(),key));}
    @GetMapping("/api/warehouse/vehicle-presence/{warehouseId}/vehicles/{vehicleId}/events") @PreAuthorize("hasRole('WAREHOUSE_WORKER')")
    public ApiResponse<List<VehiclePresenceStore.Event>> events(@PathVariable long warehouseId,@PathVariable long vehicleId) {return ApiResponse.success(presence.history(warehouseId,vehicleId));}
}
