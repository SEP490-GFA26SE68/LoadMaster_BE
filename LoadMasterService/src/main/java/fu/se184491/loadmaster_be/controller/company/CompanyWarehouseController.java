package fu.se184491.loadmaster_be.controller.company;

import fu.se184491.loadmaster_be.dto.ApiResponse;

import fu.se184491.loadmaster_be.dto.request.company.CompanyWarehouseRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyWarehouseResponse;
import fu.se184491.loadmaster_be.service.company.CompanyWarehouseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/company-warehouses")
@RequiredArgsConstructor
public class CompanyWarehouseController {

    private final CompanyWarehouseService warehouseService;

    /**
     * Danh sách kho của công ty đang đăng nhập.
     * Dispatcher có thể dùng activeOnly=true
     * để chọn kho xuất phát khi tạo Trip.
     */
    @GetMapping
    @PreAuthorize("""
    hasAnyAuthority(
        'ROLE_MANAGER',
        'ROLE_DISPATCHER',
        'ROLE_WAREHOUSE_WORKER'
    )
    """)
    public ResponseEntity<ApiResponse<List<CompanyWarehouseResponse>>>
    getWarehouses(
            @RequestParam(defaultValue = "false")
            boolean activeOnly
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        warehouseService.getWarehouses(activeOnly)
                )
        );
    }

    /**
     * Xem chi tiết một kho.
     */
    @GetMapping("/{id}")
    @PreAuthorize("""
    hasAnyAuthority(
        'ROLE_MANAGER',
        'DISPATCHER',
        'WAREHOUSE_WORKER'
    )
    """)
    public ResponseEntity<ApiResponse<CompanyWarehouseResponse>>
    getWarehouse(@PathVariable Long id) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        warehouseService.getWarehouse(id)
                )
        );
    }

    /**
     * Đăng ký kho mới.
     */
    @PostMapping
    @PreAuthorize(
        "hasRole('MANAGER')"
    )
    public ResponseEntity<ApiResponse<CompanyWarehouseResponse>>
    createWarehouse(
            @Valid @RequestBody CompanyWarehouseRequest request
    ) {
        CompanyWarehouseResponse result =
                warehouseService.createWarehouse(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Tạo kho thành công",
                        result
                ));
    }

    /**
     * Cập nhật tên, địa chỉ và tọa độ kho.
     */
    @PutMapping("/{id}")
    @PreAuthorize(
        "hasRole('MANAGER')"
    )
    public ResponseEntity<ApiResponse<CompanyWarehouseResponse>>
    updateWarehouse(
            @PathVariable Long id,
            @Valid @RequestBody CompanyWarehouseRequest request
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        warehouseService.updateWarehouse(id, request)
                )
        );
    }

    /**
     * Chọn kho xuất phát mặc định.
     */
    @PatchMapping("/{id}/default")
    @PreAuthorize(
        "hasRole('MANAGER')"
    )
    public ResponseEntity<ApiResponse<CompanyWarehouseResponse>>
    setDefaultWarehouse(@PathVariable Long id) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        warehouseService.setDefaultWarehouse(id)
                )
        );
    }

    /**
     * Kích hoạt hoặc ngừng sử dụng kho.
     */
    @PatchMapping("/{id}/active")
    @PreAuthorize(
        "hasRole('MANAGER')"
    )
    public ResponseEntity<ApiResponse<CompanyWarehouseResponse>>
    setWarehouseActive(
            @PathVariable Long id,
            @RequestParam boolean active
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        warehouseService.setWarehouseActive(id, active)
                )
        );
    }
}
