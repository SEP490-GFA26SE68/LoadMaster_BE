package fu.se184491.loadmaster_be.controller.company;

import fu.se184491.loadmaster_be.dto.request.company.UpdateCompanyProfileRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.company.CompanyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/company/profile")
@RequiredArgsConstructor
public class CompanyProfileController {

    private final CompanyService companyService;
    private final CurrentUserService currentUserService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'DISPATCHER') or hasAuthority('COMPANY_ADMIN')")
    public ResponseEntity<CompanyResponse> getCompanyProfile() {
        Long companyId = currentUserService.getCurrentCompanyId();
        return ResponseEntity.ok(companyService.getCompanyProfile(companyId));
    }

    @PatchMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('COMPANY_ADMIN')")
    public ResponseEntity<CompanyResponse> updateCompanyProfile(
            @Valid @RequestBody UpdateCompanyProfileRequest request) {
        Long companyId = currentUserService.getCurrentCompanyId();
        return ResponseEntity.ok(companyService.updateCompanyProfile(companyId, request));
    }
}
