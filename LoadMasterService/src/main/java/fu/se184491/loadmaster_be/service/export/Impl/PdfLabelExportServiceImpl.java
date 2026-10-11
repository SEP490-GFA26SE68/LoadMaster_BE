package fu.se184491.loadmaster_be.service.export.Impl;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.service.export.PdfLabelExportService;
import fu.se184491.loadmaster_be.service.qr.QrCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfLabelExportServiceImpl implements PdfLabelExportService {

    private static final int MAX_PACKAGES = 200;
    /** A4 = 595 × 842 pt. 2 columns → each cell ~265pt wide. */
    private static final float LABEL_WIDTH_PT  = 265f;
    private static final float LABEL_HEIGHT_PT = 180f;
    /** QR image size: 3.5cm ≈ 99.2pt (2cm was too small for warehouse camera scanning). */
    private static final float QR_SIZE_PT = 99.2f;

    /** Unicode fonts bundled in src/main/resources/fonts (Noto Sans, SIL OFL 1.1). */
    private static final String FONT_REGULAR_PATH = "fonts/NotoSans-Regular.ttf";
    private static final String FONT_BOLD_PATH = "fonts/NotoSans-Bold.ttf";

    private final CargoPackageRepository cargoPackageRepository;
    private final QrCodeService qrCodeService;

    private volatile BaseFont regularBaseFont;
    private volatile BaseFont boldBaseFont;

    @Override
    public byte[] generateLabelsPdf(List<Long> packageIds) {
        if (packageIds.size() > MAX_PACKAGES) {
            throw new AppException(ErrorCode.EXPORT_TOO_MANY_PACKAGES);
        }

        List<CargoPackage> packages = cargoPackageRepository.findAllById(packageIds);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 20, 20, 20, 20);
            PdfWriter.getInstance(document, out);
            document.open();

            // iText requires at least one page — add blank page for empty list
            if (packages.isEmpty()) {
                document.add(new Paragraph(" "));
            } else {
                // 2-column table for the 2×2 grid
                PdfPTable page = new PdfPTable(2);
                page.setWidthPercentage(100);

                for (CargoPackage pkg : packages) {
                    PdfPCell cell = buildLabelCell(pkg);
                    page.addCell(cell);
                }

                // Pad to even number so table renders correctly
                if (packages.size() % 2 != 0) {
                    PdfPCell empty = new PdfPCell();
                    empty.setBorder(Rectangle.NO_BORDER);
                    page.addCell(empty);
                }

                document.add(page);
            }
            document.close();

            return out.toByteArray();
        } catch (DocumentException | IOException e) {
            log.error("Failed to generate PDF labels", e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PdfPCell buildLabelCell(CargoPackage pkg) throws DocumentException, IOException {
        PdfPTable inner = new PdfPTable(2);
        inner.setTotalWidth(LABEL_WIDTH_PT);
        inner.setLockedWidth(true);
        inner.setWidths(new float[]{QR_SIZE_PT + 10, LABEL_WIDTH_PT - QR_SIZE_PT - 10});

        // --- QR code column ---
        PdfPCell qrCell = new PdfPCell();
        qrCell.setBorder(Rectangle.NO_BORDER);
        try {
            byte[] qrPng = qrCodeService.generateQrPng(pkg.getQrToken());
            Image qrImage = Image.getInstance(qrPng);
            qrImage.scaleAbsolute(QR_SIZE_PT, QR_SIZE_PT);
            qrCell.addElement(qrImage);
        } catch (Exception e) {
            log.warn("Could not generate QR for package {}", pkg.getId());
            qrCell.addElement(new Phrase("QR N/A", new Font(regularBaseFont(), 8)));
        }
        inner.addCell(qrCell);

        // --- Info column ---
        PdfPCell infoCell = new PdfPCell();
        infoCell.setBorder(Rectangle.NO_BORDER);
        infoCell.setPaddingLeft(4);

        Font bold   = new Font(boldBaseFont(), 9);
        Font normal = new Font(regularBaseFont(), 8);
        Font warn   = new Font(boldBaseFont(), 9, Font.NORMAL, BaseColor.RED);

        infoCell.addElement(new Phrase(nvl(pkg.getPackageCode(), "-"), bold));

        String companyName = resolveCompanyName(pkg);
        if (companyName != null) {
            infoCell.addElement(new Phrase("Công ty: " + companyName, normal));
        }

        String hc = pkg.getHandlingClass() != null
                ? pkg.getHandlingClass().name() : HandlingClass.STANDARD.name();
        infoCell.addElement(new Phrase("Loại: " + hc, normal));

        if (HandlingClass.FRAGILE.name().equals(hc)) {
            infoCell.addElement(new Phrase("DỄ VỠ – Xử lý nhẹ tay", warn));
        }

        if (pkg.getActualWeightKg() != null) {
            infoCell.addElement(new Phrase("Khối lượng: " + pkg.getActualWeightKg() + " kg", normal));
        }
        String dimensions = formatDimensionsCm(pkg);
        if (dimensions != null) {
            infoCell.addElement(new Phrase("Kích thước: " + dimensions + " cm", normal));
        }
        String destination = resolveDestination(pkg);
        if (destination != null) {
            infoCell.addElement(new Phrase("Điểm đến: " + destination, normal));
        }


        inner.addCell(infoCell);

        // Wrap inner table in an outer cell with border
        PdfPCell outerCell = new PdfPCell();
        outerCell.addElement(inner);
        outerCell.setFixedHeight(LABEL_HEIGHT_PT);
        outerCell.setPadding(6);
        return outerCell;
    }

    private BaseFont regularBaseFont() throws DocumentException, IOException {
        if (regularBaseFont == null) {
            regularBaseFont = loadBaseFont(FONT_REGULAR_PATH);
        }
        return regularBaseFont;
    }

    private BaseFont boldBaseFont() throws DocumentException, IOException {
        if (boldBaseFont == null) {
            boldBaseFont = loadBaseFont(FONT_BOLD_PATH);
        }
        return boldBaseFont;
    }

    private static BaseFont loadBaseFont(String classpathLocation) throws DocumentException, IOException {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            return BaseFont.createFont(classpathLocation, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null);
        }
    }

    /** Destination stored on the package (import); otherwise the order's delivery stop address/name; else null. */
    private static String resolveDestination(CargoPackage pkg) {
        if (pkg.getDestination() != null && !pkg.getDestination().isBlank()) {
            return pkg.getDestination();
        }
        if (pkg.getOrder() != null && pkg.getOrder().getDeliveryStop() != null) {
            DeliveryStop stop = pkg.getOrder().getDeliveryStop();
            return nvl(stop.getAddress(), nvl(stop.getStopName(), null));
        }
        return null;
    }

    /** Company of the package itself; legacy packages (created via an order) fall back to the order's company. */
    private static String resolveCompanyName(CargoPackage pkg) {
        Company company = pkg.getCompany();
        if (company == null && pkg.getOrder() != null) {
            company = pkg.getOrder().getCompany();
        }
        return company != null && company.getCompanyName() != null && !company.getCompanyName().isBlank()
                ? company.getCompanyName()
                : null;
    }

    /**
     * "L × W × H" in centimetres, or null when the size is not fully known. Package values are
     * millimetres; each missing side falls back to the package type's catalogue size.
     */
    private static String formatDimensionsCm(CargoPackage pkg) {
        PackageType type = pkg.getPackageType();
        Integer length = firstNonNull(pkg.getActualLength(), type != null ? type.getLength() : null);
        Integer width = firstNonNull(pkg.getActualWidth(), type != null ? type.getWidth() : null);
        Integer height = firstNonNull(pkg.getActualHeight(), type != null ? type.getHeight() : null);
        if (length == null || width == null || height == null) {
            return null;
        }
        return mmToCm(length) + " × " + mmToCm(width) + " × " + mmToCm(height);
    }

    private static String mmToCm(int mm) {
        return BigDecimal.valueOf(mm).divide(BigDecimal.TEN).stripTrailingZeros().toPlainString();
    }

    private static Integer firstNonNull(Integer first, Integer second) {
        return first != null ? first : second;
    }

    private static String nvl(String s, String fallback) {
        return (s != null && !s.isBlank()) ? s : fallback;
    }
}
