package fu.se184491.loadmaster_be.controller.billing;

import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import fu.se184491.loadmaster_be.dto.response.billing.InvoiceResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.billing.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/company/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final CurrentUserService currentUserService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAuthority('COMPANY_ADMIN')")
    public ResponseEntity<List<InvoiceResponse>> getCompanyInvoices(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) InvoiceStatus status) {
        boolean isPlatformStaff = currentUserService.hasRole("SYSTEM_ADMIN") || currentUserService.hasRole("SYSTEM_MANAGER");
        Long targetCompanyId = isPlatformStaff ? companyId : currentUserService.getCurrentCompanyId();
        return ResponseEntity.ok(invoiceService.getCompanyInvoices(targetCompanyId, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAuthority('COMPANY_ADMIN')")
    public ResponseEntity<InvoiceResponse> getInvoiceById(@PathVariable Long id) {
        boolean isPlatformStaff = currentUserService.hasRole("SYSTEM_ADMIN") || currentUserService.hasRole("SYSTEM_MANAGER");
        Long callerCompanyId = isPlatformStaff ? null : currentUserService.getCurrentCompanyId();
        return ResponseEntity.ok(invoiceService.getInvoiceById(id, callerCompanyId, isPlatformStaff));
    }
}
