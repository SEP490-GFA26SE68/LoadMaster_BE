package fu.se184491.loadmaster_be.service.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Component
public class OtpHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public OtpHasher(
            @Value("${app.brevo.security.otp-hmac-secret}") String secret
    ) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(
            String email,
            String otp
    ) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);

            mac.init(
                    new SecretKeySpec(
                            secret,
                            HMAC_ALGORITHM
                    )
            );

            String value =
                    email + ":" + otp;

            byte[] digest =
                    mac.doFinal(
                            value.getBytes(StandardCharsets.UTF_8)
                    );

            return HexFormat.of().formatHex(digest);

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to hash OTP",
                    ex
            );
        }
    }
}