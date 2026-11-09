
package fu.se184491.loadmaster_be.service.qr.Impl;

import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.qr.AesQrTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

@Slf4j
@Service
public class AesQrTokenServiceImpl implements AesQrTokenService {

    private static final String TRANSFORMATION =
            "AES/CBC/PKCS5Padding";

    private final SecretKeySpec keySpec;
    private final IvParameterSpec legacyIvSpec;

    public AesQrTokenServiceImpl(
            @Value("${app.security.qr-aes-secret}") String secret
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "QR_AES_SECRET environment variable is required"
            );
        }

        try {
            MessageDigest sha =
                    MessageDigest.getInstance("SHA-256");

            byte[] key = sha.digest(
                    secret.getBytes(StandardCharsets.UTF_8)
            );

            // ===== THÊM ĐOẠN NÀY =====
            MessageDigest fingerprintDigest =
                    MessageDigest.getInstance("SHA-256");

            byte[] fingerprint =
                    fingerprintDigest.digest(key);

            log.info(
                    "QR AES key fingerprint: {}",
                    java.util.HexFormat.of()
                            .formatHex(fingerprint, 0, 8)
            );
            // ===== KẾT THÚC =====

            byte[] ivHash = sha.digest(
                    ("LoadMaster-QR-IV:" + secret)
                            .getBytes(StandardCharsets.UTF_8)
            );

            this.keySpec = new SecretKeySpec(key, "AES");

            this.legacyIvSpec = new IvParameterSpec(
                    Arrays.copyOf(ivHash, 16)
            );

            log.info("QR AES service initialized successfully");

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to initialize QR AES key", e
            );
        }
    }

    @Override
    public String encrypt(String plainQrToken) {

        if (plainQrToken == null || plainQrToken.isBlank()) {
            return plainQrToken;
        }

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    keySpec,
                    legacyIvSpec
            );

            byte[] encrypted = cipher.doFinal(
                    plainQrToken.getBytes(StandardCharsets.UTF_8)
            );

            return Base64.getEncoder()
                    .encodeToString(encrypted);

        } catch (Exception e) {
            log.error("QR encryption failed", e);

            throw new AppException(
                    ErrorCode.QR_ENCRYPTION_FAILED
            );
        }
    }

    @Override
    public String decrypt(String dbCiphertext) {

        if (dbCiphertext == null || dbCiphertext.isBlank()) {
            return dbCiphertext;
        }

        try {
            byte[] decoded =
                    Base64.getDecoder().decode(dbCiphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    keySpec,
                    legacyIvSpec
            );

            byte[] decrypted = cipher.doFinal(decoded);

            return new String(
                    decrypted,
                    StandardCharsets.UTF_8
            );

        } catch (Exception e) {
            log.error(
                    "QR decryption failed: {}",
                    e.getClass().getSimpleName(),
                    e
            );

            throw new AppException(
                    ErrorCode.QR_ENCRYPTION_FAILED
            );
        }
    }
}
