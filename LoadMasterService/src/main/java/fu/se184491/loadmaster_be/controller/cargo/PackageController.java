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
    @PreAuthorize("hasRole('DISPATCHER') or hasAuthority('PACKAGE_MANAGE')")
    public PackageResponse createPackage(@Valid @RequestBody PackageRequest request) {
        return packageService.createPackage(getCurrentCompanyId(), request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DISPATCHER') or hasAuthority('PACKAGE_MANAGE')")
    public PackageResponse updatePackage(@PathVariable Long id, @Valid @RequestBody PackageRequest request) {
        return packageService.updatePackage(getCurrentCompanyId(), id, request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('DISPATCHER') or hasAuthority('PACKAGE_MANAGE')")
    public PackageResponse getPackageById(@PathVariable Long id) {
        return packageService.getPackageById(getCurrentCompanyId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('DISPATCHER') or hasAuthority('PACKAGE_MANAGE')")
    public void deletePackage(@PathVariable Long id) {
        packageService.deletePackage(getCurrentCompanyId(), id);
    }

    @GetMapping
    @PreAuthorize("hasRole('DISPATCHER') or hasAuthority('PACKAGE_MANAGE')")
    public Page<PackageResponse> getAllPackages(
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Long packageTypeId,
            @RequestParam(required = false) String packageCode,
            Pageable pageable) {
        return packageService.getAllPackages(getCurrentCompanyId(), orderId, packageTypeId, packageCode, pageable);
    }
}
