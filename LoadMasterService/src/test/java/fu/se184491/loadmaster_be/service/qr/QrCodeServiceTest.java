package fu.se184491.loadmaster_be.service.qr;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.qr.Impl.AesQrTokenServiceImpl;
import jakarta.persistence.Convert;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class QrCodeServiceTest {

    @Test
    void aesQrTokenServiceImpl_encryptsAndDecryptsTwoWay_andBlocksStolenCiphertextScan() {
        AesQrTokenService aesService = new AesQrTokenServiceImpl("LoadMasterQrAesSecretKey2026!@#");
        String plainUuid = "964f8796-a497-473e-98ec-b2faf09dff4e";

        // 1. Encrypting to DB column produces AES ciphertext different from plaintext
        String dbCiphertext = aesService.encrypt(plainUuid);
        assertNotNull(dbCiphertext);
        assertNotEquals(plainUuid, dbCiphertext);
        assertFalse(dbCiphertext.contains("964f8796"));

        // 2. Decrypting from DB column restores exact original UUID (2-way AES)
        String decrypted = aesService.decrypt(dbCiphertext);
        assertEquals(plainUuid, decrypted);

        // 3. If a hacker steals dbCiphertext from DB and tries to scan it,
        //    encrypting the stolen ciphertext produces a different lookup value
        String hackerScanLookup = aesService.encrypt(dbCiphertext);
        assertNotEquals(dbCiphertext, hackerScanLookup);
    }

    @Test
    void aesQrTokenServiceImpl_whenSecretBlank_throwsAppExceptionWithQrEncryptionFailedErrorCode() {
        AppException ex = assertThrows(
                AppException.class,
                () -> new AesQrTokenServiceImpl("   ")
        );
        assertEquals(ErrorCode.QR_ENCRYPTION_FAILED, ex.getErrorCode());
    }

    @Test
    void cargoPackageQrTokenField_usesAesQrTokenServiceImplConverter() throws NoSuchFieldException {
        Field qrTokenField = CargoPackage.class.getDeclaredField("qrToken");
        Convert convertAnnotation = qrTokenField.getAnnotation(Convert.class);

        assertNotNull(convertAnnotation, "CargoPackage.qrToken must have @Convert(converter = AesQrTokenServiceImpl.class)");
        assertEquals(AesQrTokenServiceImpl.class, convertAnnotation.converter());
    }
}
