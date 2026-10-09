package fu.se184491.loadmaster_be.controller.cargo;

import fu.se184491.loadmaster_be.dto.request.cargo.PackageRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.PackageResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.cargo.PackageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/packages")
@RequiredArgsConstructor
public class PackageController {

    private final PackageService packageService;

    @Autowired(required = false)
    private CurrentUserService currentUserService;

    private Long getCurrentCompanyId() {
        try {
            if (currentUserService != null) {
                return currentUserService.getCurrentCompanyId();
            }
        } catch (Exception ignored) {
        }
        return 1L;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('PACKAGE_MANAGE', 'DISPATCHER')")
    public PackageResponse createPackage(@Valid @RequestBody PackageRequest request) {
        return packageService.createPackage(getCurrentCompanyId(), request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PACKAGE_MANAGE', 'DISPATCHER')")
    public PackageResponse updatePackage(@PathVariable Long id, @Valid @RequestBody PackageRequest request) {
        return packageService.updatePackage(getCurrentCompanyId(), id, request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PACKAGE_MANAGE', 'DISPATCHER')")
    public PackageResponse getPackageById(@PathVariable Long id) {
        return packageService.getPackageById(getCurrentCompanyId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('PACKAGE_MANAGE', 'DISPATCHER')")
    public void deletePackage(@PathVariable Long id) {
        packageService.deletePackage(getCurrentCompanyId(), id);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PACKAGE_MANAGE', 'DISPATCHER')")
    public Page<PackageResponse> getAllPackages(
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Long packageTypeId,
            Pageable pageable) {
        return packageService.getAllPackages(getCurrentCompanyId(), orderId, packageTypeId, pageable);
    }
}
