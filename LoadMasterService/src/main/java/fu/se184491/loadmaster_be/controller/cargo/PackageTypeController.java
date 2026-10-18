
package fu.se184491.loadmaster_be.controller.cargo;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.packagetype.PackageTypeRequest;
import fu.se184491.loadmaster_be.dto.response.packagetype.PackageTypeResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.cargo.PackageTypeService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/package-types")
@RequiredArgsConstructor
public class PackageTypeController {

    private final PackageTypeService packageTypeService;
    private final CurrentUserService currentUserService;

    private Long getCurrentCompanyId() {
        User currentUser = currentUserService.getCurrentUser();

        if (currentUser.getCompany() == null) {
            throw new AppException(ErrorCode.COMPANY_REQUIRED);
        }

        return currentUser.getCompany().getId();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PACKAGE_TYPE_MANAGE')")
    public ResponseEntity<ApiResponse<PackageTypeResponse>> createPackageType(
            @Valid @RequestBody PackageTypeRequest request) {

        PackageTypeResponse result =
                packageTypeService.createPackageType(
                        getCurrentCompanyId(),
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo loại kiện thành công", result));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PACKAGE_TYPE_MANAGE')")
    public ResponseEntity<ApiResponse<PackageTypeResponse>> updatePackageType(
            @PathVariable Long id,
            @Valid @RequestBody PackageTypeRequest request) {

        PackageTypeResponse result =
                packageTypeService.updatePackageType(
                        getCurrentCompanyId(),
                        id,
                        request
                );

        return ResponseEntity.ok(
                ApiResponse.success("Cập nhật loại kiện thành công", result)
        );
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PACKAGE_TYPE_MANAGE')")
    public ResponseEntity<ApiResponse<PackageTypeResponse>> getPackageTypeById(
            @PathVariable Long id) {

        PackageTypeResponse result =
                packageTypeService.getPackageTypeById(
                        getCurrentCompanyId(),
                        id
                );

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PACKAGE_TYPE_MANAGE', 'ROLE_DISPATCHER', 'ROLE_MANAGER')")
    public ResponseEntity<PageResponse<PackageTypeResponse>> getAllPackageTypes(
            Pageable pageable) {

        Page<PackageTypeResponse> result =
                packageTypeService.getAllPackageTypes(
                        getCurrentCompanyId(),
                        pageable
                );

        return ResponseEntity.ok(PageResponse.of(result));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PACKAGE_TYPE_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> deletePackageType(
            @PathVariable Long id) {

        packageTypeService.deletePackageType(
                getCurrentCompanyId(),
                id
        );

        return ResponseEntity.ok(
                ApiResponse.success("Xóa loại kiện thành công")
        );
    }
}
