package fu.se184491.loadmaster_be.controller.payment;

import fu.se184491.loadmaster_be.helpers.LogMasks;
import fu.se184491.loadmaster_be.client.VnPayClient;
import fu.se184491.loadmaster_be.config.FrontendUrls;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.payment.MockCheckoutAccess;
import fu.se184491.loadmaster_be.service.payment.PaymentCallbackHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final VnPayClient vnPayClient;
    private final MockCheckoutAccess mockCheckoutAccess;

    @Value(FrontendUrls.URL)
    private String frontendUrl = FrontendUrls.DEFAULT;

    @PostMapping("/webhook/vnpay")
    public ResponseEntity<Map<String, String>> handleVnPayWebhookPost(@RequestParam Map<String, String> allParams) {
        log.info("Received VNPay webhook (POST): {}", LogMasks.vnpayParams(allParams));
        Map<String, String> response = paymentCallbackHandler.handleVnPayCallback(allParams);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/webhook/vnpay")
    public ResponseEntity<Map<String, String>> handleVnPayWebhookGet(@RequestParam Map<String, String> allParams) {
        log.info("Received VNPay webhook (GET): {}", LogMasks.vnpayParams(allParams));
        Map<String, String> response = paymentCallbackHandler.handleVnPayCallback(allParams);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/webhook/vnpay-return")
    public ResponseEntity<Void> handleVnPayReturn(@RequestParam Map<String, String> allParams) {
        log.info("Received VNPay Return redirect: {}", LogMasks.vnpayParams(allParams));
        Map<String, String> callbackResult = paymentCallbackHandler.handleVnPayCallback(allParams);

        String txnRef = allParams.get("vnp_TxnRef");
        String responseCode = allParams.get("vnp_ResponseCode");
        boolean isSuccess = "00".equals(responseCode) && "00".equals(callbackResult.get("RspCode"));

        String redirectUrl;
        if (txnRef != null && txnRef.startsWith("REG_")) {
            redirectUrl = FrontendUrls.join(frontendUrl, "/dang-ky-thanh-cong?orderCode="
                    + java.net.URLEncoder.encode(txnRef, java.nio.charset.StandardCharsets.UTF_8));
        } else {
            String status = isSuccess ? "success" : "failed";
            redirectUrl = FrontendUrls.join(frontendUrl, "/goi-cuoc?paymentStatus=" + status
                    + (txnRef != null ? "&txnRef=" + java.net.URLEncoder.encode(txnRef, java.nio.charset.StandardCharsets.UTF_8) : ""));
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectUrl))
                .build();
    }

    /**
     * Xác nhận thanh toán giả lập. Chỉ dùng được khi máy chủ bật cấu hình {@code vnpay.mock}; tắt cấu hình đó
     * (môi trường thật) thì endpoint trả "không tìm thấy giao dịch" và không đánh dấu giao dịch nào đã trả.
     * Giao dịch của công ty đòi đăng nhập (401) và chỉ công ty sở hữu giao dịch xác nhận được (công ty khác: 404);
     * giao dịch đăng ký trả phí {@code REG_…} của khách chưa có tài khoản thì không đòi đăng nhập.
     */
    @GetMapping("/api/payment/mock-checkout")
    public ResponseEntity<Map<String, Object>> mockCheckout(@RequestParam String txnRef) {
        if (!vnPayClient.isMock()) {
            log.warn("Rejected mock checkout for txnRef {}: vnpay.mock is disabled", txnRef);
            throw new AppException(ErrorCode.PAYMENT_TRANSACTION_NOT_FOUND);
        }
        mockCheckoutAccess.requireAllowed(txnRef);
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
