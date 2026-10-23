
package fu.se184491.loadmaster_be.controller.credit;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.service.credit.CreditService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/credits")
@RequiredArgsConstructor
public class InternalCreditController {

    private final CreditService creditService;

    @PostMapping("/deduct")
    @PreAuthorize("hasAuthority('ROLE_OPTIMIZATION_CREDIT')")
    public ResponseEntity<ApiResponse<Void>> deductCredit(
            @RequestBody Map<String, Object> request
    ) {
        Long companyId = Long.valueOf(
                request.get("company_id").toString()
        );

        String reference = request.get("reference").toString();

        creditService.deductCredit(companyId, reference);

        return ResponseEntity.ok(
                ApiResponse.success("Trừ credit thành công", null)
        );
    }

    @PostMapping("/refund")
    @PreAuthorize("hasAuthority('ROLE_OPTIMIZATION_CREDIT')")
    public ResponseEntity<ApiResponse<Void>> refundCredit(
            @RequestBody Map<String, Object> request
    ) {
        Long companyId = Long.valueOf(
                request.get("company_id").toString()
        );

        String reference = request.get("reference").toString();

        creditService.refundCredit(companyId, reference);

        return ResponseEntity.ok(
                ApiResponse.success("Hoàn credit thành công", null)
        );
    }
}
