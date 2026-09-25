package fu.se184491.loadmaster_be.controller.warehouse;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.warehouse.RecordDeviationRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.StartLoadingRequest;
import fu.se184491.loadmaster_be.dto.response.warehouse.CompleteLoadingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.ConfirmPlacementResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.DeviationResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.StartLoadingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.WarehouseTaskResponse;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/warehouse")
@RequiredArgsConstructor
@Tag(name = "Warehouse", description = "APIs for warehouse loading tasks and execution")
public class WarehouseController {

    private final WarehouseService warehouseService;

    @GetMapping("/tasks")
    @Operation(summary = "Get warehouse tasks", description = "Retrieve list of trips with approved load plans that are ready for loading")
    public ResponseEntity<ApiResponse<List<WarehouseTaskResponse>>> getTasks(
            @RequestParam(value = "companyId", required = false) Long companyId,
            @RequestParam(value = "workerId", required = false) Long workerId) {
        List<WarehouseTaskResponse> tasks = warehouseService.getTasks(companyId, workerId);
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách nhiệm vụ bốc xếp thành công", tasks));
    }

    @PostMapping("/tasks/{tripId}/start")
    @Operation(summary = "Start loading", description = "Start loading execution for an approved trip and return placements ordered by step sequence")
    public ResponseEntity<ApiResponse<StartLoadingResponse>> startLoading(
            @PathVariable("tripId") Long tripId,
            @RequestBody(required = false) StartLoadingRequest request) {
        StartLoadingResponse response = warehouseService.startLoading(tripId, request);
        return ResponseEntity.ok(ApiResponse.success("Bắt đầu quá trình bốc xếp thành công", response));
    }

    @PostMapping("/placements/{id}/confirm")
    @Operation(summary = "Confirm placement", description = "Confirm that a package placement has been loaded correctly according to sequence")
    public ResponseEntity<ApiResponse<ConfirmPlacementResponse>> confirmPlacement(
            @PathVariable("id") Long id,
            @RequestParam(value = "workerId", required = false) Long workerId) {
        ConfirmPlacementResponse response = warehouseService.confirmPlacement(id, workerId);
        return ResponseEntity.ok(ApiResponse.success("Xác nhận vị trí kiện hàng thành công", response));
    }

    @PostMapping("/placements/{id}/deviation")
    @Operation(summary = "Record deviation", description = "Record actual position deviation and reason when loading a package")
    public ResponseEntity<ApiResponse<DeviationResponse>> recordDeviation(
            @PathVariable("id") Long id,
            @RequestBody @Valid RecordDeviationRequest request) {
        DeviationResponse response = warehouseService.recordDeviation(id, request);
        return ResponseEntity.ok(ApiResponse.success("Ghi nhận sai lệch vị trí kiện hàng thành công", response));
    }

    @PostMapping("/tasks/{id}/complete")
    @Operation(summary = "Complete loading", description = "Complete the loading execution once all placements have been confirmed or recorded as deviated")
    public ResponseEntity<ApiResponse<CompleteLoadingResponse>> completeLoading(
            @PathVariable("id") Long id) {
        CompleteLoadingResponse response = warehouseService.completeLoading(id);
        return ResponseEntity.ok(ApiResponse.success("Hoàn tất quá trình bốc xếp hàng thành công", response));
    }
}
