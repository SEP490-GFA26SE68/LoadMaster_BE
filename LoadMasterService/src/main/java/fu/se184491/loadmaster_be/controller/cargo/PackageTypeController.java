package fu.se184491.loadmaster_be.controller.cargo;

import fu.se184491.loadmaster_be.dto.request.packagetype.PackageTypeRequest;
import fu.se184491.loadmaster_be.dto.response.packagetype.PackageTypeResponse;
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

    // TODO: Extract companyId from Security Context
    private Long getCurrentCompanyId() {
        return 1L;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PACKAGE_TYPE_MANAGE')")
    public ResponseEntity<PackageTypeResponse> createPackageType(@Valid @RequestBody PackageTypeRequest request) {
        return new ResponseEntity<>(packageTypeService.createPackageType(getCurrentCompanyId(), request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PACKAGE_TYPE_MANAGE')")
    public ResponseEntity<PackageTypeResponse> updatePackageType(
            @PathVariable Long id,
            @Valid @RequestBody PackageTypeRequest request) {
        return ResponseEntity.ok(packageTypeService.updatePackageType(getCurrentCompanyId(), id, request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PACKAGE_TYPE_MANAGE')")
    public ResponseEntity<PackageTypeResponse> getPackageTypeById(@PathVariable Long id) {
        return ResponseEntity.ok(packageTypeService.getPackageTypeById(getCurrentCompanyId(), id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PACKAGE_TYPE_MANAGE')")
    public ResponseEntity<Page<PackageTypeResponse>> getAllPackageTypes(Pageable pageable) {
        return ResponseEntity.ok(packageTypeService.getAllPackageTypes(getCurrentCompanyId(), pageable));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PACKAGE_TYPE_MANAGE')")
    public ResponseEntity<Void> deletePackageType(@PathVariable Long id) {
        packageTypeService.deletePackageType(getCurrentCompanyId(), id);
        return ResponseEntity.noContent().build();
    }
}
