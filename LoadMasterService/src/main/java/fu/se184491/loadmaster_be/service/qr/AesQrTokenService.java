package fu.se184491.loadmaster_be.service.qr;

/**
 * Service for two-way AES-256 encryption and decryption of package QR tokens.
 */
public interface AesQrTokenService {

    /**
     * Encrypts a plaintext QR token into a deterministic AES-256 Base64 ciphertext for DB storage.
     *
     * @param plainQrToken the plaintext QR token
     * @return AES-256 encrypted Base64 string
     */
    String encrypt(String plainQrToken);

    /**
     * Decrypts an AES-256 Base64 ciphertext from the DB back into the plaintext QR token.
     *
     * @param dbCiphertext the encrypted QR token stored in the DB
     * @return decrypted plaintext QR token
     */
    String decrypt(String dbCiphertext);
}
