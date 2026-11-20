package fu.se184491.loadmaster_be.controller.vehicle;
import fu.se184491.loadmaster_be.dto.*;
import fu.se184491.loadmaster_be.service.vehicle.VehicleTransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor
public class VehicleTransferController {
    private final VehicleTransferService service;
    public record Request(@NotNull @Positive Long vehicleId,@NotNull @Positive Long sourceWarehouseId,@NotNull @Positive Long destinationWarehouseId,@NotBlank @Size(max=500) String reason){}
    @GetMapping("/api/vehicle-transfers/warehouses") @PreAuthorize("hasRole('DISPATCHER')")
    public ApiResponse<List<Map<String,Object>>> warehouses(){return ApiResponse.success(service.warehouses());}
    @GetMapping("/api/vehicle-transfers/candidates") @PreAuthorize("hasRole('DISPATCHER')")
    public PageResponse<Map<String,Object>> candidates(@RequestParam long warehouseId,@RequestParam(defaultValue="0") int page){return PageResponse.of(service.candidates(warehouseId,page));}
    @GetMapping("/api/vehicle-transfers") @PreAuthorize("hasRole('DISPATCHER')")
    public PageResponse<Map<String,Object>> list(@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="0") int page){return PageResponse.of(service.list(null,status,page));}
    @PostMapping("/api/vehicle-transfers") @PreAuthorize("hasRole('DISPATCHER')")
    public ApiResponse<Map<String,Object>> request(@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Request r){return ApiResponse.success(service.request(r.vehicleId(),r.sourceWarehouseId(),r.destinationWarehouseId(),r.reason(),key));}
    @PostMapping("/api/vehicle-transfers/{id}/cancel") @PreAuthorize("hasRole('DISPATCHER')")
    public ApiResponse<Map<String,Object>> cancel(@PathVariable long id){return ApiResponse.success(service.cancel(id));}
    @GetMapping("/api/warehouse/vehicle-presence/{warehouse}/transfers") @PreAuthorize("hasRole('WAREHOUSE_WORKER')")
    public PageResponse<Map<String,Object>> warehouseList(@PathVariable long warehouse,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="0") int page){return PageResponse.of(service.list(warehouse,status,page));}
    @PostMapping("/api/warehouse/vehicle-presence/{warehouse}/transfers/{id}/departure") @PreAuthorize("hasRole('WAREHOUSE_WORKER')")
    public ApiResponse<Map<String,Object>> departure(@PathVariable long warehouse,@PathVariable long id,@RequestHeader("Idempotency-Key") UUID key){return ApiResponse.success(service.confirm(warehouse,id,false,key));}
    @PostMapping("/api/warehouse/vehicle-presence/{warehouse}/transfers/{id}/arrival") @PreAuthorize("hasRole('WAREHOUSE_WORKER')")
    public ApiResponse<Map<String,Object>> arrival(@PathVariable long warehouse,@PathVariable long id,@RequestHeader("Idempotency-Key") UUID key){return ApiResponse.success(service.confirm(warehouse,id,true,key));}
}
