package fu.se184491.loadmaster_be.controller.payment;

import fu.se184491.loadmaster_be.service.payment.PaymentCallbackHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookController {

    private final PaymentCallbackHandler paymentCallbackHandler;

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

    @GetMapping("/api/payment/mock-checkout")
    public ResponseEntity<Map<String, Object>> mockCheckout(@RequestParam String txnRef) {
        log.info("Executing mock checkout for txnRef: {}", txnRef);
        Map<String, String> mockParams = new HashMap<>();
        mockParams.put("vnp_TxnRef", txnRef);
        mockParams.put("vnp_ResponseCode", "00");

        Map<String, String> callbackResult = paymentCallbackHandler.handleVnPayCallback(mockParams);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Mock payment executed successfully");
        result.put("txnRef", txnRef);
        result.put("callbackResult", callbackResult);
        return ResponseEntity.ok(result);
    }
}
