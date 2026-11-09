package fu.se184491.loadmaster_be.controller.warehouse;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.driver.ManualUnloadRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.RecordSealRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.ReportDamageRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.ReportShortageRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.StagePackageRequest;
import fu.se184491.loadmaster_be.dto.response.driver.ManualConfirmationResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.DamageResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.SealResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.StagingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.StagingShortageResponse;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseIncidentService;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseStagingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Việc của kho trong phiên xếp ngoài xác nhận vị trí: soạn hàng (đối chiếu, xác nhận tay, báo thiếu), xác nhận tay và kiện hỏng lúc xếp,
 * số seal sau khi xếp xong. Phiên trả về ở {@code GET /api/warehouse/tasks/{tripId}} mang trạng thái của các việc này.
 */
@RestController
@RequestMapping("/api/warehouse")
@RequiredArgsConstructor
@Tag(name = "Warehouse staging", description = "Staging, shortages, manual confirmations, damaged packages and seal of a loading session")
@PreAuthorize("hasRole('WAREHOUSE_WORKER')")
public class WarehouseStagingController {

    private final WarehouseStagingService stagingService;
    private final WarehouseIncidentService incidentService;

    @PostMapping("/tasks/{tripId}/staging/packages/{packageId}/confirm")
    @Operation(summary = "Stage a package", description = "Verify a package into the staging area by QR or typed code; staging it again changes nothing")
    public ResponseEntity<ApiResponse<StagingResponse>> stage(
            @PathVariable("tripId") Long tripId,
            @PathVariable("packageId") Long packageId,
            @RequestBody(required = false) StagePackageRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã soạn kiện", stagingService.stage(tripId, packageId, request)));
    }

    @PostMapping("/tasks/{tripId}/staging/packages/{packageId}/manual")
    @Operation(summary = "Stage a package manually", description = "The package counts as staged; a dispatcher approves or rejects it")
    public ResponseEntity<ApiResponse<StagingResponse>> stageManually(
            @PathVariable("tripId") Long tripId,
            @PathVariable("packageId") Long packageId,
            @RequestBody @Valid ManualUnloadRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã gửi xác nhận tay",
                stagingService.stageManually(tripId, packageId, request)));
    }

    @PostMapping("/tasks/{tripId}/staging/packages/{packageId}/shortage")
    @Operation(summary = "Report a missing package", description = "The dispatcher decides: keep searching or drop it from the trip")
    public ResponseEntity<ApiResponse<StagingShortageResponse>> reportShortage(
            @PathVariable("tripId") Long tripId,
            @PathVariable("packageId") Long packageId,
            @RequestBody(required = false) @Valid ReportShortageRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã báo thiếu kiện",
                stagingService.reportShortage(tripId, packageId, request)));
    }

    @PostMapping("/placements/{id}/manual")
    @Operation(summary = "Confirm a placement manually", description = "The package counts as loaded; a dispatcher approves or rejects it")
    public ResponseEntity<ApiResponse<ManualConfirmationResponse>> confirmManually(
            @PathVariable("id") Long id,
            @RequestBody @Valid ManualUnloadRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã gửi xác nhận tay", incidentService.confirmPlacementManually(id, request)));
    }

    @PostMapping("/placements/{id}/damaged")
    @Operation(summary = "Report a damaged package",
            description = "Left at the warehouse when nothing rests on it in the plan; otherwise the trip returns to PLANNED")
    public ResponseEntity<ApiResponse<DamageResponse>> reportDamaged(
            @PathVariable("id") Long id,
            @RequestBody(required = false) ReportDamageRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã ghi kiện hỏng", incidentService.reportDamaged(id, request)));
    }

    @PostMapping("/tasks/{tripId}/seal")
    @Operation(summary = "Record the seal number", description = "Only after loading is completed and before the driver departs")
    public ResponseEntity<ApiResponse<SealResponse>> recordSeal(
            @PathVariable("tripId") Long tripId,
            @RequestBody RecordSealRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã ghi số seal", incidentService.recordSeal(tripId, request)));
    }
}
