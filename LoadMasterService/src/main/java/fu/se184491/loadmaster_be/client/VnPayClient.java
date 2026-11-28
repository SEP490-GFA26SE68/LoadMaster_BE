package fu.se184491.loadmaster_be.client;

import fu.se184491.loadmaster_be.config.payment.VnPayConfig;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class VnPayClient {

    private final VnPayConfig config;
    private final fu.se184491.loadmaster_be.service.payment.MockPaymentPolicy mockPolicy;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public boolean isMock() {
        return mockPolicy.isEnabled();
    }

    public String createPaymentUrl(PaymentTransaction txn, String orderInfo, String ipAddress) {
        String txnRef = txn.getGatewayTransactionId() != null ? txn.getGatewayTransactionId() : String.valueOf(txn.getId());
        return createPaymentUrl(txnRef, txn.getAmountVnd(), orderInfo, ipAddress);
    }

    public String createPaymentUrl(String txnRef, BigDecimal amountVnd, String orderInfo, String ipAddress) {
        if (isMock()) {
            return "/api/payment/mock-checkout?txnRef=" + txnRef;
        }

        Map<String, String> vnpParams = new HashMap<>();
        vnpParams.put("vnp_Version", "2.1.0");
        vnpParams.put("vnp_Command", "pay");
        vnpParams.put("vnp_TmnCode", getCleanTmnCode());

        long amount = amountVnd.multiply(BigDecimal.valueOf(100)).longValue();
        vnpParams.put("vnp_Amount", String.valueOf(amount));
        vnpParams.put("vnp_CurrCode", "VND");
        vnpParams.put("vnp_TxnRef", txnRef != null ? txnRef.trim() : "");
        vnpParams.put("vnp_OrderInfo", sanitizeOrderInfo(orderInfo));
        vnpParams.put("vnp_OrderType", "other");
        vnpParams.put("vnp_Locale", "vn");
        vnpParams.put("vnp_ReturnUrl", getCleanReturnUrl());

        String resolvedIp = (ipAddress != null && !ipAddress.isBlank()) ? ipAddress.trim() : "127.0.0.1";
        if ("0:0:0:0:0:0:0:1".equals(resolvedIp) || "::1".equals(resolvedIp)) {
            resolvedIp = "127.0.0.1";
        } else if (resolvedIp.startsWith("::ffff:")) {
            resolvedIp = resolvedIp.substring(7);
        }
        vnpParams.put("vnp_IpAddr", resolvedIp);

        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        LocalDateTime now = LocalDateTime.now(zone);
        vnpParams.put("vnp_CreateDate", now.format(DATE_FORMATTER));
        vnpParams.put("vnp_ExpireDate", now.plusMinutes(15).format(DATE_FORMATTER));

        List<String> fieldNames = new ArrayList<>(vnpParams.keySet());
        Collections.sort(fieldNames);

        StringBuilder hashData = new StringBuilder();
        StringBuilder query = new StringBuilder();
        boolean first = true;
        for (String fieldName : fieldNames) {
            String fieldValue = vnpParams.get(fieldName);
            if (fieldValue != null && !fieldValue.isEmpty()) {
                if (!first) {
                    hashData.append('&');
                    query.append('&');
                }
                hashData.append(fieldName).append('=').append(URLEncoder.encode(fieldValue, StandardCharsets.UTF_8));

                query.append(URLEncoder.encode(fieldName, StandardCharsets.UTF_8));
                query.append('=');
                query.append(URLEncoder.encode(fieldValue, StandardCharsets.UTF_8));
                first = false;
            }
        }

        String queryUrl = query.toString();
        String vnpSecureHash = hmacSHA512(getCleanHashSecret(), hashData.toString());
        queryUrl += "&vnp_SecureHash=" + vnpSecureHash;

        log.info("Generated VNPay payment URL for txnRef={} (secureHash length={})", txnRef, vnpSecureHash.length());
        return getCleanVnpUrl() + "?" + queryUrl;
    }

    public boolean verifyCallback(Map<String, String> fields) {
        String secureHash = fields.get("vnp_SecureHash");
        if (secureHash == null || secureHash.isBlank()) {
            return false;
        }

        Map<String, String> cleanFields = new HashMap<>(fields);
        cleanFields.remove("vnp_SecureHashType");
        cleanFields.remove("vnp_SecureHash");

        List<String> fieldNames = new ArrayList<>(cleanFields.keySet());
        Collections.sort(fieldNames);

        StringBuilder hashData = new StringBuilder();
        boolean first = true;
        for (String fieldName : fieldNames) {
            String fieldValue = cleanFields.get(fieldName);
            if (fieldValue != null && !fieldValue.isEmpty()) {
                if (!first) {
                    hashData.append('&');
                }
                hashData.append(fieldName).append('=').append(URLEncoder.encode(fieldValue, StandardCharsets.UTF_8));
                first = false;
            }
        }

        String checkSum = hmacSHA512(getCleanHashSecret(), hashData.toString());
        return checkSum.equalsIgnoreCase(secureHash.trim());
    }

    public static String sanitizeOrderInfo(String orderInfo) {
        if (orderInfo == null || orderInfo.isBlank()) {
            return "LoadMaster Payment";
        }
        String normalized = Normalizer.normalize(orderInfo, Normalizer.Form.NFD);
        String noAccents = normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String sanitized = noAccents.replace('đ', 'd').replace('Đ', 'D')
                .replaceAll("[^a-zA-Z0-9\\s-_]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (sanitized.isEmpty()) {
            return "LoadMaster Payment";
        }
        return sanitized.length() > 255 ? sanitized.substring(0, 255) : sanitized;
    }

    public static String hmacSHA512(String key, String data) {
        try {
            Mac hmac512 = Mac.getInstance("HmacSHA512");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
            hmac512.init(secretKey);
            byte[] result = hmac512.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(2 * result.length);
            for (byte b : result) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception ex) {
            log.error("Failed to generate HMAC SHA512 hash", ex);
            return "";
        }
    }

    private String getCleanHashSecret() {
        String secret = config.getHashSecret();
        if (secret == null) return "";
        return secret.trim().replaceAll("^['\"]|['\"]$", "");
    }

    private String getCleanTmnCode() {
        String tmn = config.getTmnCode();
        if (tmn == null) return "";
        return tmn.trim().replaceAll("^['\"]|['\"]$", "");
    }

    private String getCleanReturnUrl() {
        String returnUrl = config.getReturnUrl();
        if (returnUrl == null) return "";
        return returnUrl.trim();
    }

    private String getCleanVnpUrl() {
        String vnpUrl = config.getVnpUrl();
        if (vnpUrl == null) return "https://sandbox.vnpaytest.vn/paymentv2/vpcpay.html";
        return vnpUrl.trim();
    }
}
