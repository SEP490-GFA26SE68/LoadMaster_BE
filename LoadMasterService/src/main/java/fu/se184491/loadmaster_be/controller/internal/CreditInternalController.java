package fu.se184491.loadmaster_be.controller.internal;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.credit.CreditInternalRequest;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/credits")
@RequiredArgsConstructor
@Slf4j
public class CreditInternalController {

    private final CreditService creditService;

    @PostMapping("/deduct")
    public ResponseEntity<ApiResponse<Void>> deductCredit(@Valid @RequestBody CreditInternalRequest request) {
        log.info("Internal request to deduct credit: companyId={}, reference={}", request.getCompanyId(), request.getReference());
        creditService.deductCredit(request.getCompanyId(), request.getReference());
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("Credit deducted successfully")
                .build());
    }

    @PostMapping("/refund")
    public ResponseEntity<ApiResponse<Void>> refundCredit(@Valid @RequestBody CreditInternalRequest request) {
        log.info("Internal request to refund credit: companyId={}, reference={}", request.getCompanyId(), request.getReference());
        creditService.refundCredit(request.getCompanyId(), request.getReference());
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("Credit refunded successfully")
                .build());
    }
}
