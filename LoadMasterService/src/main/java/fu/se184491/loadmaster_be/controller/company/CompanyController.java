package fu.se184491.loadmaster_be.controller.company;

import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.company.CompanyRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyOptionResponse;
import fu.se184491.loadmaster_be.dto.response.company.CompanyResponse;
import fu.se184491.loadmaster_be.service.company.CompanyService;
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
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @PostMapping
    @PreAuthorize("hasAuthority('SYSTEM_MANAGER')")
    public ResponseEntity<CompanyResponse> createCompany(@Valid @RequestBody CompanyRequest request) {
        return new ResponseEntity<>(companyService.createCompany(request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_MANAGER')")
    public ResponseEntity<CompanyResponse> updateCompany(
            @PathVariable Long id,
            @Valid @RequestBody CompanyRequest request) {
        return ResponseEntity.ok(companyService.updateCompany(id, request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_MANAGER')")
    public ResponseEntity<CompanyResponse> getCompanyById(@PathVariable Long id) {
        return ResponseEntity.ok(companyService.getCompanyById(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SYSTEM_MANAGER')")
    public ResponseEntity<Page<CompanyResponse>> getAllCompanies(Pageable pageable) {
        return ResponseEntity.ok(companyService.getAllCompanies(pageable));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('SYSTEM_MANAGER')")
    public ResponseEntity<CompanyResponse> changeCompanyStatus(
            @PathVariable Long id,
            @RequestParam CompanyStatus status) {
        return ResponseEntity.ok(companyService.changeCompanyStatus(id, status));
    }

    @PreAuthorize("hasAuthority('USERS_CREATE')")
    @GetMapping("/options")
    public ApiResponse<List<CompanyOptionResponse>> getCompanies() {
        return ApiResponse.success(
                "Companies retrieved successfully",
                companyService.getCompanyOptions()
        );
    }
}
