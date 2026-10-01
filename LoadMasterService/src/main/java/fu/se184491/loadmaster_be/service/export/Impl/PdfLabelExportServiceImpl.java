package fu.se184491.loadmaster_be.service.export.Impl;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.service.export.PdfLabelExportService;
import fu.se184491.loadmaster_be.service.qr.QrCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfLabelExportServiceImpl implements PdfLabelExportService {

    private static final int MAX_PACKAGES = 200;
    /** A4 = 595 × 842 pt. 2 columns → each cell ~265pt wide. */
    private static final float LABEL_WIDTH_PT  = 265f;
    private static final float LABEL_HEIGHT_PT = 180f;
    /** QR image size: 2cm ≈ 56.7pt */
    private static final float QR_SIZE_PT = 56.7f;

    private final CargoPackageRepository cargoPackageRepository;
    private final QrCodeService qrCodeService;

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
            qrCell.addElement(new Phrase("QR N/A", FontFactory.getFont(FontFactory.HELVETICA, 8)));
        }
        inner.addCell(qrCell);

        // --- Info column ---
        PdfPCell infoCell = new PdfPCell();
        infoCell.setBorder(Rectangle.NO_BORDER);
        infoCell.setPaddingLeft(4);

        Font bold   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Font warn   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, BaseColor.RED);

        infoCell.addElement(new Phrase(nvl(pkg.getPackageCode(), "-"), bold));

        String hc = pkg.getHandlingClass() != null
                ? pkg.getHandlingClass().name() : HandlingClass.STANDARD.name();
        infoCell.addElement(new Phrase("Type: " + hc, normal));

        if (HandlingClass.FRAGILE.name().equals(hc)) {
            infoCell.addElement(new Phrase("⚠ FRAGILE – Handle with care", warn));
        }

        if (pkg.getActualWeightKg() != null) {
            infoCell.addElement(new Phrase("Weight: " + pkg.getActualWeightKg() + " kg", normal));
        }
        if (pkg.getOrder() != null && pkg.getOrder().getDeliveryStop() != null) {
            infoCell.addElement(new Phrase(
                    "Dest: " + pkg.getOrder().getDeliveryStop().toString(), normal));
        }

        inner.addCell(infoCell);

        // Wrap inner table in an outer cell with border
        PdfPCell outerCell = new PdfPCell();
        outerCell.addElement(inner);
        outerCell.setFixedHeight(LABEL_HEIGHT_PT);
        outerCell.setPadding(6);
        return outerCell;
    }

    private static String nvl(String s, String fallback) {
        return (s != null && !s.isBlank()) ? s : fallback;
    }
}
