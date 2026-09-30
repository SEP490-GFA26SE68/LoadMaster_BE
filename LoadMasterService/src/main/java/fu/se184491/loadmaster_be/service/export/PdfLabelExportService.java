package fu.se184491.loadmaster_be.service.export;

import java.util.List;

/**
 * Generates a PDF containing printable QR labels for the given packages.
 */
public interface PdfLabelExportService {

    /**
     * Generates a PDF byte array with 4 labels per A4 page (2×2 grid).
     * Each label includes: QR code image, package_code, handling_class,
     * dimensions (L×W×H mm), weight (kg), destination.
     * FRAGILE packages include an additional warning text.
     *
     * @param packageIds IDs of packages to include (max 200)
     * @return PDF bytes
     * @throws fu.se184491.loadmaster_be.exception.AppException with
     *         {@code ErrorCode.EXPORT_TOO_MANY_PACKAGES} if {@code packageIds.size() > 200}
     */
    byte[] generateLabelsPdf(List<Long> packageIds);
}
