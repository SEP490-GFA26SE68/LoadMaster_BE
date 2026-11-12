package fu.se184491.loadmaster_be.helpers;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Che dữ liệu cá nhân và bí mật trước khi ghi log: email, mã số thuế, mã QR, tham số VNPay (chữ ký, ngân hàng, nội
 * dung đơn). Log chỉ cần đủ để lần ra bản ghi, không cần giá trị đầy đủ.
 */
public final class LogMasks {

    /** Tham số VNPay đủ để lần ra giao dịch; chữ ký, ngân hàng, thẻ, nội dung đơn không bao giờ vào log. */
    private static final Set<String> VNPAY_LOGGED_KEYS =
            Set.of("vnp_TxnRef", "vnp_ResponseCode", "vnp_TransactionStatus", "vnp_Amount");

    private LogMasks() {
    }

    /** {@code nguyen.van.a@congty.vn} → {@code n***@congty.vn}. */
    public static String email(String email) {
        if (email == null) {
            return "null";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** Chỉ giữ ba ký tự cuối của mã số thuế. */
    public static String taxCode(String taxCode) {
        if (taxCode == null) {
            return "null";
        }
        return taxCode.length() <= 3 ? "***" : "***" + taxCode.substring(taxCode.length() - 3);
    }

    /** Chỉ giữ bốn ký tự đầu của mã bí mật (mã QR, mã phiên). */
    public static String token(String token) {
        if (token == null) {
            return "null";
        }
        return token.length() <= 4 ? "***" : token.substring(0, 4) + "***";
    }

    /** Chỉ các tham số VNPay trong {@link #VNPAY_LOGGED_KEYS}, xếp theo tên. */
    public static Map<String, String> vnpayParams(Map<String, String> params) {
        Map<String, String> logged = new TreeMap<>();
        if (params == null) {
            return logged;
        }
        params.forEach((key, value) -> {
            if (VNPAY_LOGGED_KEYS.contains(key)) {
                logged.put(key, value);
            }
        });
        return logged;
    }
}
