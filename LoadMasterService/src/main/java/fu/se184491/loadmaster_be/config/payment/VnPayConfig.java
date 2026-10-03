package fu.se184491.loadmaster_be.config.payment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "vnpay")
@Getter
@Setter
public class VnPayConfig {
    private String tmnCode = "DEMOTMN1";
    private String hashSecret = "DEMOHASHSECRET1234567890ABCDEF";
    private String vnpUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private String returnUrl = "http://localhost:3000/payment/vnpay-return";
    private boolean mock = false;
}
