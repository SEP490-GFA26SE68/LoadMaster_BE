package fu.se184491.loadmaster_be.controller.company;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.service.company.WarehouseStaffAssignmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/company-warehouses")
@PreAuthorize("hasRole('MANAGER')")
public class WarehouseStaffAssignmentController {
    private final WarehouseStaffAssignmentService service;
    public record Assign(@NotNull @Positive Long userId) {}
    @GetMapping("/staff")
    public ApiResponse<List<WarehouseStaffAssignmentService.Staff>> staff() {return ApiResponse.success(service.staff());}
    @GetMapping("/{warehouseId}/staff-assignments")
    public ApiResponse<List<WarehouseStaffAssignmentService.Assignment>> assignments(@PathVariable long warehouseId) {return ApiResponse.success(service.assignments(warehouseId));}
    @PostMapping("/{warehouseId}/staff-assignments")
    public ApiResponse<List<WarehouseStaffAssignmentService.Assignment>> assign(@PathVariable long warehouseId,@Valid @RequestBody Assign body) {return ApiResponse.success(service.change(warehouseId,body.userId(),true));}
    @DeleteMapping("/{warehouseId}/staff-assignments/{userId}")
    public ApiResponse<List<WarehouseStaffAssignmentService.Assignment>> revoke(@PathVariable long warehouseId,@PathVariable long userId) {return ApiResponse.success(service.change(warehouseId,userId,false));}
    @GetMapping("/{warehouseId}/staff-assignments/history")
    public ApiResponse<List<WarehouseStaffAssignmentService.Change>> history(@PathVariable long warehouseId,@RequestParam(defaultValue="0") int page) {return ApiResponse.success(service.history(warehouseId,page));}
}
