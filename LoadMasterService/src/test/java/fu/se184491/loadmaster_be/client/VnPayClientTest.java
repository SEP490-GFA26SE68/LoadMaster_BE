package fu.se184491.loadmaster_be.client;

import fu.se184491.loadmaster_be.config.payment.VnPayConfig;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.PaymentStatus;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class VnPayClientTest {

    private VnPayConfig config;
    private VnPayClient client;

    @BeforeEach
    void setUp() {
        config = new VnPayConfig();
        config.setTmnCode("TESTTMN");
        config.setHashSecret("SECRETKEY1234567890ABCDEF123456");
        config.setVnpUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        config.setReturnUrl("http://localhost:3000/payment/vnpay-return");
        config.setMock(false);

        client = new VnPayClient(config);
    }

    @Test
    @DisplayName("createPaymentUrl should generate valid URL containing vnp_SecureHash and required params")
    void testCreatePaymentUrl() {
        PaymentTransaction txn = PaymentTransaction.builder()
                .id(1001L)
                .gateway(PaymentGateway.VNPAY)
                .gatewayTransactionId("VNP-TXN-1001")
                .amountVnd(new BigDecimal("500000"))
                .status(PaymentStatus.PENDING)
                .build();

        String url = client.createPaymentUrl(txn, "Thanh toan goi Pro", "127.0.0.1");

        assertNotNull(url);
        assertTrue(url.startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?"));
        assertTrue(url.contains("vnp_TmnCode=TESTTMN"));
        assertTrue(url.contains("vnp_Amount=50000000")); // 500,000 * 100
        assertTrue(url.contains("vnp_TxnRef=VNP-TXN-1001"));
        assertTrue(url.contains("vnp_SecureHash="));
    }

    @Test
    @DisplayName("verifyCallback should return true for valid hash, false for tampered data")
    void testVerifyCallback() {
        PaymentTransaction txn = PaymentTransaction.builder()
                .id(1002L)
                .gateway(PaymentGateway.VNPAY)
                .gatewayTransactionId("VNP-TXN-1002")
                .amountVnd(new BigDecimal("100000"))
                .status(PaymentStatus.PENDING)
                .build();

        String url = client.createPaymentUrl(txn, "Topup 100k", "127.0.0.1");
        String queryString = url.substring(url.indexOf("?") + 1);

        Map<String, String> params = new HashMap<>();
        for (String pair : queryString.split("&")) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = pair.substring(0, idx);
                String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }

        assertTrue(client.verifyCallback(params));

        // Tamper amount
        params.put("vnp_Amount", "99999999");
        assertFalse(client.verifyCallback(params));
    }
}
