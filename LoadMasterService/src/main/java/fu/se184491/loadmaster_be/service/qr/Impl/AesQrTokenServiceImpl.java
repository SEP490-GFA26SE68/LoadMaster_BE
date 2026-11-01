package fu.se184491.loadmaster_be.service.qr.Impl;

import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.qr.AesQrTokenService;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * Implementation of {@link AesQrTokenService} and JPA {@link AttributeConverter}
 * for two-way deterministic AES-256 encryption of CargoPackage.qrToken.
 */
@Slf4j
@Service
@Converter
public class AesQrTokenServiceImpl implements AesQrTokenService, AttributeConverter<String, String> {

    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

    private static volatile SecretKeySpec sharedKeySpec;
    private static volatile IvParameterSpec sharedIvSpec;

    static {
        String envSecret = resolveEnvSecret();
        if (envSecret != null && !envSecret.isBlank()) {
            initKey(envSecret);
        }
    }

    public AesQrTokenServiceImpl() {
        // Default constructor required by JPA / Hibernate
    }

    @Autowired
    public AesQrTokenServiceImpl(@Value("${app.security.qr-aes-secret}") String secret) {
        initKey(secret);
    }

    private static String resolveEnvSecret() {
        String secret = System.getenv("QR_AES_SECRET");
        if (secret != null && !secret.isBlank()) {
            return secret;
        }
        try {
            Path dotEnv = Path.of(".env");
            if (Files.exists(dotEnv)) {
                for (String line : Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("QR_AES_SECRET=")) {
                        return trimmed.substring("QR_AES_SECRET=".length()).trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // Ignored if .env is not readable in current working directory
        }
        return null;
    }

    private static synchronized void initKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new AppException(ErrorCode.QR_ENCRYPTION_FAILED);
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha256.digest(secret.getBytes(StandardCharsets.UTF_8));
            byte[] ivHash = sha256.digest(("LoadMaster-QR-IV:" + secret).getBytes(StandardCharsets.UTF_8));
            byte[] ivBytes = Arrays.copyOf(ivHash, 16);

            sharedKeySpec = new SecretKeySpec(keyBytes, "AES");
            sharedIvSpec = new IvParameterSpec(ivBytes);
        } catch (Exception e) {
            log.error("Failed to initialize AES-256 key for QR token", e);
            throw new AppException(ErrorCode.QR_ENCRYPTION_FAILED);
        }
    }

    @Override
    public String encrypt(String plainQrToken) {
        if (plainQrToken == null || plainQrToken.isBlank()) {
            return plainQrToken;
        }
        if (sharedKeySpec == null || sharedIvSpec == null) {
            throw new AppException(ErrorCode.QR_ENCRYPTION_FAILED);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, sharedKeySpec, sharedIvSpec);
            byte[] encrypted = cipher.doFinal(plainQrToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("Failed to encrypt qrToken with AES-256", e);
            throw new AppException(ErrorCode.QR_ENCRYPTION_FAILED);
        }
    }

    @Override
    public String decrypt(String dbCiphertext) {
        if (dbCiphertext == null || dbCiphertext.isBlank()) {
            return dbCiphertext;
        }
        if (sharedKeySpec == null || sharedIvSpec == null) {
            throw new AppException(ErrorCode.QR_ENCRYPTION_FAILED);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(dbCiphertext);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, sharedKeySpec, sharedIvSpec);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt qrToken with AES-256", e);
            throw new AppException(ErrorCode.QR_ENCRYPTION_FAILED);
        }
    }

    @Override
    public String convertToDatabaseColumn(String plainQrToken) {
        return encrypt(plainQrToken);
    }

    @Override
    public String convertToEntityAttribute(String dbCiphertext) {
        return decrypt(dbCiphertext);
    }
}
