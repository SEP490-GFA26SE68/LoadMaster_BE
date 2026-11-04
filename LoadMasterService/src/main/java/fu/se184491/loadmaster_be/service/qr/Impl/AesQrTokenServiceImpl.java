
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
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

@Slf4j
@Service
@Converter(autoApply = false)
public class AesQrTokenServiceImpl
        implements AesQrTokenService,
        AttributeConverter<String, String> {

    private static final String VERSION = "v2:";
    private static final String GCM = "AES/GCM/NoPadding";
    private static final String LEGACY_CBC =
            "AES/CBC/PKCS5Padding";

    private static final int NONCE_SIZE = 12;
    private static final int TAG_BITS = 128;

    private static final byte[] AAD =
            "LoadMaster:packages.qr_token:v2"
                    .getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom RANDOM =
            new SecureRandom();

    private static final Pattern UUID_PATTERN =
            Pattern.compile(
                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-" +
                            "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                            "[0-9a-fA-F]{12}$"
            );

    private static volatile SecretKeySpec sharedKeySpec;
    private static volatile IvParameterSpec sharedLegacyIv;

    public AesQrTokenServiceImpl() {
        // Hibernate converter constructor
    }

    @Autowired
    public AesQrTokenServiceImpl(
            @Value("${app.security.qr-aes-secret}")
            String secret
    ) {
        initKey(secret);
    }

    private static synchronized void initKey(
            String secret
    ) {
        if (secret == null || secret.isBlank()) {
            throw new AppException(
                    ErrorCode.QR_ENCRYPTION_FAILED
            );
        }

        try {
            MessageDigest sha =
                    MessageDigest.getInstance("SHA-256");

            byte[] key = sha.digest(
                    secret.getBytes(StandardCharsets.UTF_8)
            );

            byte[] ivHash = sha.digest(
                    ("LoadMaster-QR-IV:" + secret)
                            .getBytes(StandardCharsets.UTF_8)
            );

            sharedKeySpec =
                    new SecretKeySpec(key, "AES");

            // Chỉ dùng để đọc dữ liệu AES-CBC cũ.
            sharedLegacyIv = new IvParameterSpec(
                    Arrays.copyOf(ivHash, 16)
            );

        } catch (Exception e) {
            log.error("QR key initialization failed", e);
            throw new AppException(
                    ErrorCode.QR_ENCRYPTION_FAILED
            );
        }
    }

    private static void requireKey() {
        if (sharedKeySpec == null ||
                sharedLegacyIv == null) {
            throw new AppException(
                    ErrorCode.QR_ENCRYPTION_FAILED
            );
        }
    }

    public boolean isCurrentFormat(String value) {
        return value != null &&
                value.startsWith(VERSION);
    }

    public boolean isLegacyUuid(String value) {
        return value != null &&
                UUID_PATTERN.matcher(value).matches();
    }

    @Override
    public String encrypt(String plainQrToken) {
        if (plainQrToken == null ||
                plainQrToken.isBlank()) {
            return plainQrToken;
        }

        requireKey();

        try {
            byte[] nonce = new byte[NONCE_SIZE];
            RANDOM.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(GCM);

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    sharedKeySpec,
                    new GCMParameterSpec(
                            TAG_BITS,
                            nonce
                    )
            );

            cipher.updateAAD(AAD);

            byte[] encrypted = cipher.doFinal(
                    plainQrToken.getBytes(
                            StandardCharsets.UTF_8
                    )
            );

            byte[] combined = new byte[
                    nonce.length + encrypted.length
                    ];

            System.arraycopy(
                    nonce, 0,
                    combined, 0,
                    nonce.length
            );

            System.arraycopy(
                    encrypted, 0,
                    combined, nonce.length,
                    encrypted.length
            );

            return VERSION +
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(combined);

        } catch (Exception e) {
            log.error("QR AES-GCM encryption failed", e);
            throw new AppException(
                    ErrorCode.QR_ENCRYPTION_FAILED
            );
        }
    }

    @Override
    public String decrypt(String storedToken) {
        if (storedToken == null ||
                storedToken.isBlank()) {
            return storedToken;
        }

        // Tạm hỗ trợ UUID plaintext cũ.
        if (isLegacyUuid(storedToken)) {
            return storedToken;
        }

        requireKey();

        try {
            if (isCurrentFormat(storedToken)) {
                return decryptGcm(storedToken);
            }

            // Tạm hỗ trợ AES-CBC Base64 cũ.
            return decryptLegacyCbc(storedToken);

        } catch (Exception e) {
            log.error(
                    "QR decryption failed: invalid token format or key",
                    e
            );

            throw new AppException(
                    ErrorCode.QR_ENCRYPTION_FAILED
            );
        }
    }

    private String decryptGcm(String stored)
            throws Exception {

        byte[] combined =
                Base64.getUrlDecoder().decode(
                        stored.substring(VERSION.length())
                );

        if (combined.length < NONCE_SIZE + 16) {
            throw new IllegalArgumentException(
                    "Invalid AES-GCM token length"
            );
        }

        byte[] nonce = Arrays.copyOfRange(
                combined, 0, NONCE_SIZE
        );

        byte[] ciphertext = Arrays.copyOfRange(
                combined, NONCE_SIZE, combined.length
        );

        Cipher cipher = Cipher.getInstance(GCM);

        cipher.init(
                Cipher.DECRYPT_MODE,
                sharedKeySpec,
                new GCMParameterSpec(TAG_BITS, nonce)
        );

        cipher.updateAAD(AAD);

        byte[] plain = cipher.doFinal(ciphertext);

        return new String(
                plain, StandardCharsets.UTF_8
        );
    }

    private String decryptLegacyCbc(String stored)
            throws Exception {

        byte[] decoded =
                Base64.getDecoder().decode(stored);

        Cipher cipher = Cipher.getInstance(LEGACY_CBC);

        cipher.init(
                Cipher.DECRYPT_MODE,
                sharedKeySpec,
                sharedLegacyIv
        );

        String plain = new String(
                cipher.doFinal(decoded),
                StandardCharsets.UTF_8
        );

        // Các token của CargoPackage được tạo dạng UUID.
        if (!isLegacyUuid(plain)) {
            throw new IllegalArgumentException(
                    "Unexpected legacy QR token format"
            );
        }

        return plain;
    }

    @Override
    public String convertToDatabaseColumn(String plain) {
        return encrypt(plain);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return decrypt(stored);
    }
}
