package fu.se184491.loadmaster_be.helpers;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogMasksTest {

    @Test
    void emailKeepsFirstLetterAndDomain() {
        assertThat(LogMasks.email("nguyen.van.a@congty.vn")).isEqualTo("n***@congty.vn");
        assertThat(LogMasks.email("khong-phai-email")).isEqualTo("***");
        assertThat(LogMasks.email(null)).isEqualTo("null");
    }

    @Test
    void taxCodeKeepsLastThreeDigits() {
        assertThat(LogMasks.taxCode("0312345678")).isEqualTo("***678");
        assertThat(LogMasks.taxCode("12")).isEqualTo("***");
    }

    @Test
    void tokenKeepsFirstFourCharacters() {
        assertThat(LogMasks.token("abcd1234efgh")).isEqualTo("abcd***");
        assertThat(LogMasks.token("abc")).isEqualTo("***");
    }

    @Test
    void vnpayParamsDropSignatureBankAndOrderInfo() {
        Map<String, String> logged = LogMasks.vnpayParams(Map.of(
                "vnp_TxnRef", "VNP_1",
                "vnp_ResponseCode", "00",
                "vnp_Amount", "100000",
                "vnp_SecureHash", "secret",
                "vnp_BankCode", "NCB",
                "vnp_OrderInfo", "Dang ky goi Pro - Cong ty A"));

        assertThat(logged).containsOnlyKeys("vnp_TxnRef", "vnp_ResponseCode", "vnp_Amount");
    }
}
