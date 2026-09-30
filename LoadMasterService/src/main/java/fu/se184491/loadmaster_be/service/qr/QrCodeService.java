package fu.se184491.loadmaster_be.service.qr;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;

/**
 * Service for generating and looking up QR codes for cargo packages.
 *
 * <p>QR content = raw qr_token string only — no URL, no JSON, no sensitive data.</p>
 */
public interface QrCodeService {

    /**
     * Generates a unique UUID v4 QR token that is guaranteed not to already exist in the DB.
     *
     * @return a unique qr_token string
     */
    String generateQrToken();

    /**
     * Generates a 300×300 PNG image encoding the given qrToken.
     *
     * @param qrToken the token to encode
     * @return PNG bytes
     * @throws fu.se184491.loadmaster_be.exception.AppException with ErrorCode.INTERNAL_SERVER_ERROR
     *         if QR generation fails
     */
    byte[] generateQrPng(String qrToken);

    /**
     * Looks up the CargoPackage entity by its qr_token.
     *
     * @param qrToken the token to search
     * @return the matching CargoPackage entity
     * @throws fu.se184491.loadmaster_be.exception.AppException with ErrorCode.PACKAGE_NOT_FOUND
     *         if no package has this token
     */
    CargoPackage lookupByToken(String qrToken);

    /**
     * Looks up a CargoPackage by its database ID.
     *
     * @param id the package ID
     * @return the matching CargoPackage entity
     * @throws fu.se184491.loadmaster_be.exception.AppException with ErrorCode.PACKAGE_NOT_FOUND
     *         if no package has this ID
     */
    CargoPackage findById(Long id);
}
