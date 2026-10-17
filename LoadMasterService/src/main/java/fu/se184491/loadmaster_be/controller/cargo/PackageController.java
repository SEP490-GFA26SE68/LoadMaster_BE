package fu.se184491.loadmaster_be.controller.cargo;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.cargo.PackageRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.PackageResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.cargo.PackageService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/packages")
@RequiredArgsConstructor
public class PackageController {

    private final PackageService packageService;
    private final CurrentUserService currentUserService;

    private Long getCurrentCompanyId() {
        return currentUserService.getCurrentCompanyId();
    }

    // ==================== CREATE ====================

    @PostMapping
    @PreAuthorize("hasAnyAuthority('PACKAGE_MANAGE', 'DISPATCHER')")
    public ResponseEntity<ApiResponse<PackageResponse>> createPackage(
            @Valid @RequestBody PackageRequest request
    ) {
        PackageResponse result = packageService.createPackage(
                getCurrentCompanyId(),
                request
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Tạo kiện hàng thành công",
                        result
                ));
    }


    @PostMapping("/batch")
    @PreAuthorize("hasAnyAuthority('PACKAGE_MANAGE', 'DISPATCHER')")
    public ResponseEntity<ApiResponse<List<PackageResponse>>> createPackages(
            @Valid @RequestBody PackageRequest request
    ) {
        List<PackageResponse> results = packageService.createPackages(
                getCurrentCompanyId(),
                request
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Tạo " + results.size() + " kiện hàng thành công",
                        results
                ));
    }


    // ==================== UPDATE ====================

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PACKAGE_MANAGE', 'DISPATCHER')")
    public ResponseEntity<ApiResponse<PackageResponse>> updatePackage(
            @PathVariable Long id,
            @Valid @RequestBody PackageRequest request
    ) {
        PackageResponse result = packageService.updatePackage(
                getCurrentCompanyId(),
                id,
                request
        );

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Cập nhật kiện hàng thành công",
                        result
                )
        );
    }

    // ==================== DELETE ====================

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PACKAGE_MANAGE', 'DISPATCHER')")
    public ResponseEntity<ApiResponse<Void>> deletePackage(
            @PathVariable Long id
    ) {
        packageService.deletePackage(
                getCurrentCompanyId(),
                id
        );

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Xóa kiện hàng thành công",
                        null
                )
        );
    }

    // ==================== GET BY ID ====================

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PACKAGE_MANAGE', 'DISPATCHER')")
    public ResponseEntity<ApiResponse<PackageResponse>> getPackageById(
            @PathVariable Long id
    ) {
        PackageResponse result = packageService.getPackageById(
                getCurrentCompanyId(),
                id
        );

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Lấy thông tin kiện hàng thành công",
                        result
                )
        );
    }

    // ==================== GET ALL ====================

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PACKAGE_MANAGE', 'DISPATCHER')")
    public ResponseEntity<PageResponse<PackageResponse>> getAllPackages(
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Long packageTypeId,
            Pageable pageable
    ) {
        Page<PackageResponse> page = packageService.getAllPackages(
                getCurrentCompanyId(),
                orderId,
                packageTypeId,
                pageable
        );

        PageResponse<PackageResponse> response = PageResponse.<PackageResponse>builder()
                .success(true)
                .message("Lấy danh sách kiện hàng thành công")
                .data(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();

        return ResponseEntity.ok(response);
    }
}