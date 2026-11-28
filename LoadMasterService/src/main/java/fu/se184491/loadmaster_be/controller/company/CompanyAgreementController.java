package fu.se184491.loadmaster_be.controller.company;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.company.SignAgreementRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyAgreementResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.company.CompanyAgreementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agreements")
@RequiredArgsConstructor
public class CompanyAgreementController {

    private final CompanyAgreementService agreementService;
    private final CurrentUserService currentUserService;
    private final fu.se184491.loadmaster_be.service.assistant.gate.ClientIpResolver clientIpResolver;

    @GetMapping("/my-agreement")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CompanyAgreementResponse> getMyAgreement() {
        User currentUser = currentUserService.getCurrentUser();
        CompanyAgreementResponse response = agreementService.getMyAgreement(currentUser);
        return ApiResponse.success(response);
    }

    @PostMapping("/sign")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CompanyAgreementResponse> signAgreement(
            @Valid @RequestBody SignAgreementRequest request,
            HttpServletRequest servletRequest
    ) {
        User currentUser = currentUserService.getCurrentUser();
        String clientIp = clientIpResolver.resolveIpv4(servletRequest);
        String userAgent = servletRequest.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > 255) {
            userAgent = userAgent.substring(0, 255);
        }
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = "Unknown Device";
        }

        CompanyAgreementResponse response = agreementService.signAgreement(currentUser, request, clientIp, userAgent);
        return ApiResponse.success("Ký thỏa thuận dịch vụ thành công", response);
    }

    @GetMapping("/company/{companyId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'SYSTEM_MANAGER')")
    public ApiResponse<CompanyAgreementResponse> getCompanyAgreement(@PathVariable Long companyId) {
        CompanyAgreementResponse response = agreementService.getCompanyAgreement(companyId);
        return ApiResponse.success(response);
    }
}
