package fu.se184491.loadmaster_be.controller.warehouse;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.warehouse.ResolveShortageRequest;
import fu.se184491.loadmaster_be.dto.response.warehouse.StagingShortageResponse;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseStagingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Kiện kho báo thiếu lúc soạn hàng: điều phối viên quyết, quản lý công ty chỉ xem. */
@RestController
@RequestMapping("/api/trips/{tripId}/staging-shortages")
@RequiredArgsConstructor
@Tag(name = "Staging shortages", description = "Dispatcher decisions on packages the warehouse could not find")
public class StagingShortageController {

    private final WarehouseStagingService stagingService;

    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    @Operation(summary = "Open shortages of a trip")
    public ResponseEntity<ApiResponse<List<StagingShortageResponse>>> listOpen(@PathVariable("tripId") String tripId) {
        return ResponseEntity.ok(ApiResponse.success("Lấy kiện kho báo thiếu thành công", stagingService.listOpenShortages(tripId)));
    }

    @PostMapping("/{shortageId}/resolve")
    @PreAuthorize("hasRole('DISPATCHER')")
    @Operation(summary = "Resolve a shortage",
            description = "KEEP_SEARCHING closes the report; DROP removes the package and returns the trip to PLANNED with stale plans")
    public ResponseEntity<ApiResponse<StagingShortageResponse>> resolve(
            @PathVariable("tripId") String tripId,
            @PathVariable("shortageId") Long shortageId,
            @Valid @RequestBody ResolveShortageRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã quyết báo thiếu",
                stagingService.resolveShortage(tripId, shortageId, request)));
    }
}
