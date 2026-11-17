package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;

/** Package IDs are never label evidence. CODE is the business Package Code; QR accepts the printed QR/barcode. */
public final class WarehouseLabelVerifier {
    private WarehouseLabelVerifier() {}
    public static void requireMatch(CargoPackage pkg, StagingMethod method, String code) {
        if (pkg == null || method == null || method == StagingMethod.MANUAL || code == null || code.isBlank())
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        boolean matches = method == StagingMethod.CODE
                ? same(code, pkg.getPackageCode())
                : same(code, pkg.getQrToken()) || same(code, pkg.getTrackingBarcode());
        if (!matches) throw new AppException(ErrorCode.PACKAGE_CODE_MISMATCH);
    }
    private static boolean same(String entered, String expected) {
        return expected != null && !expected.isBlank() && entered.trim().equalsIgnoreCase(expected.trim());
    }
}
