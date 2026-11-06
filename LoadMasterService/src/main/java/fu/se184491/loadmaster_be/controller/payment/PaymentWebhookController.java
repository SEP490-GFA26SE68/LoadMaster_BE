package fu.se184491.loadmaster_be.controller.payment;

import fu.se184491.loadmaster_be.service.payment.PaymentCallbackHandler;
import fu.se184491.loadmaster_be.service.payment.MockPaymentPolicy;
import org.springframework.security.access.prepost.PreAuthorize;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookController {

    private final PaymentCallbackHandler paymentCallbackHandler;
    private final MockPaymentPolicy mockPaymentPolicy;

    @PostMapping("/webhook/vnpay")
    public ResponseEntity<Map<String, String>> handleVnPayWebhookPost(@RequestParam Map<String, String> allParams) {
        log.info("Received VNPay webhook (POST): {}", allParams);
        Map<String, String> response = paymentCallbackHandler.handleVnPayCallback(allParams);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/webhook/vnpay")
    public ResponseEntity<Map<String, String>> handleVnPayWebhookGet(@RequestParam Map<String, String> allParams) {
        log.info("Received VNPay webhook (GET): {}", allParams);
        Map<String, String> response = paymentCallbackHandler.handleVnPayCallback(allParams);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/webhook/vnpay-return")
    public ResponseEntity<Void> handleVnPayReturn(@RequestParam Map<String, String> allParams) {
        log.info("Received VNPay Return redirect: {}", allParams);
        paymentCallbackHandler.handleVnPayCallback(allParams);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("http://localhost:5173/goi-cuoc"))
                .build();
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'COMPANY_ADMIN')")
    @GetMapping("/api/payment/mock-checkout")
    public ResponseEntity<Map<String, Object>> mockCheckout(@RequestParam String txnRef) {
        mockPaymentPolicy.requireEnabled();
        Map<String, String> callbackResult = paymentCallbackHandler.handleMockCheckout(txnRef);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Mock payment executed successfully");
        result.put("txnRef", txnRef);
        result.put("callbackResult", callbackResult);
        return ResponseEntity.ok(result);
    }
}
