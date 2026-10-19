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
import fu.se184491.loadmaster_be.service.cargo.PackageOwnershipService;
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
    /**
     * A4 = 595 × 842 pt. 2 columns → each cell ~265pt wide.
     */
    private static final float LABEL_WIDTH_PT = 265f;
    private static final float LABEL_HEIGHT_PT = 180f;
    /**
     * QR image size: 3.5cm ≈ 99.2pt (2cm was too small for warehouse camera scanning).
     */
    private static final float QR_SIZE_PT = 99.2f;

    /**
     * Unicode fonts bundled in src/main/resources/fonts (Noto Sans, SIL OFL 1.1).
     */
    private static final String FONT_REGULAR_PATH = "fonts/NotoSans-Regular.ttf";
    private static final String FONT_BOLD_PATH = "fonts/NotoSans-Bold.ttf";

    private static final String LOGO_PATH =
            "images/loadmaster-logo.png";

    private static final BaseColor BRAND_BLUE =
            new BaseColor(0, 102, 255);

    private static final BaseColor DARK_TEXT =
            new BaseColor(23, 37, 55);

    private static final BaseColor MUTED_TEXT =
            new BaseColor(100, 116, 139);

    private static final BaseColor BORDER_COLOR =
            new BaseColor(210, 220, 230);

    private final CargoPackageRepository cargoPackageRepository;
    private final QrCodeService qrCodeService;
    private final PackageOwnershipService packageOwnershipService;

    private volatile BaseFont regularBaseFont;
    private volatile BaseFont boldBaseFont;

    @Override
    public byte[] generateLabelsPdf(List<Long> packageIds, Long companyId) {
        if (packageIds.size() > MAX_PACKAGES) {
            throw new AppException(ErrorCode.EXPORT_TOO_MANY_PACKAGES);
        }

        List<CargoPackage> packages = cargoPackageRepository.findAllById(packageIds);
        if (packages.stream().anyMatch(pkg -> !packageOwnershipService.belongsToCompany(pkg, companyId))) {
            throw new AppException(ErrorCode.PACKAGE_NOT_FOUND);
        }

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
    private static Image loadLogo()
            throws IOException, BadElementException {

        ClassPathResource resource =
                new ClassPathResource(LOGO_PATH);

        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readAllBytes();

            Image logo = Image.getInstance(bytes);
            logo.scaleToFit(28f, 28f);

            return logo;
        }
    }

    private PdfPCell buildLabelCell(CargoPackage pkg)
            throws DocumentException, IOException {

        Font brandFont = new Font(
                boldBaseFont(), 13, Font.NORMAL, BRAND_BLUE
        );

        Font headingFont = new Font(
                boldBaseFont(), 9, Font.NORMAL, DARK_TEXT
        );

        Font normalFont = new Font(
                regularBaseFont(), 7, Font.NORMAL, DARK_TEXT
        );

        Font mutedFont = new Font(
                regularBaseFont(), 7, Font.NORMAL, MUTED_TEXT
        );

        Font warningFont = new Font(
                boldBaseFont(), 7, Font.NORMAL, BaseColor.RED
        );

        // Toàn bộ nội dung của một nhãn.
        PdfPTable content = new PdfPTable(1);
        content.setWidthPercentage(100);

        // ===== HEADER: LOGO + TÊN HỆ THỐNG =====

        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        header.setWidths(new float[]{34f, 190f});

        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setPadding(0);

        try {
            Image logo = loadLogo();
            logoCell.addElement(logo);
        } catch (Exception e) {
            log.warn("Cannot load LoadMaster logo", e);
        }

        header.addCell(logoCell);

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setPaddingLeft(4);
        titleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Paragraph brand = new Paragraph(
                "LoadMaster", brandFont
        );
        brand.setSpacingAfter(1f);

        titleCell.addElement(brand);
        titleCell.addElement(new Phrase(
                "NHÃN KIỆN HÀNG / PACKAGE LABEL",
                mutedFont
        ));

        header.addCell(titleCell);

        PdfPCell headerCell = new PdfPCell();
        headerCell.setBorder(Rectangle.NO_BORDER);
        headerCell.setPaddingBottom(5);
        headerCell.addElement(header);

        content.addCell(headerCell);

        // ===== BODY: QR + THÔNG TIN KIỆN =====

        PdfPTable body = new PdfPTable(2);
        body.setWidthPercentage(100);
        body.setWidths(new float[]{108f, 145f});

        PdfPCell qrCell = new PdfPCell();
        qrCell.setBorder(Rectangle.NO_BORDER);
        qrCell.setPadding(0);
        qrCell.setHorizontalAlignment(Element.ALIGN_CENTER);

        // QR vẫn được Backend tạo từ qrToken trong database.
        byte[] qrPng =
                qrCodeService.generateQrPng(pkg.getQrToken());

        Image qr = Image.getInstance(qrPng);
        qr.scaleToFit(94f, 94f);
        qrCell.addElement(qr);

        body.addCell(qrCell);

        PdfPCell infoCell = new PdfPCell();
        infoCell.setBorder(Rectangle.NO_BORDER);
        infoCell.setPaddingLeft(4);
        infoCell.setPaddingTop(2);

        Paragraph code = new Paragraph(
                nvl(pkg.getPackageCode(), "-"),
                headingFont
        );
        code.setSpacingAfter(4f);
        infoCell.addElement(code);

        // Tên công ty sở hữu kiện hàng.
        String companyName = resolveCompanyName(pkg);

        if (companyName != null) {
            Paragraph company = new Paragraph(
                    "Công ty: " + companyName,
                    normalFont
            );
            company.setSpacingAfter(3f);
            infoCell.addElement(company);
        }

        if (pkg.getPackageName() != null
                && !pkg.getPackageName().isBlank()) {
            infoCell.addElement(new Paragraph(
                    pkg.getPackageName(), normalFont
            ));
        }

        if (pkg.getActualWeightKg() != null) {
            infoCell.addElement(new Paragraph(
                    "Khối lượng: "
                            + pkg.getActualWeightKg()
                            + " kg",
                    normalFont
            ));
        }

        String dimensions = formatDimensionsCm(pkg);
        if (dimensions != null) {
            infoCell.addElement(new Paragraph(
                    "Kích thước: " + dimensions + " cm",
                    normalFont
            ));
        }

        String destination = resolveDestination(pkg);
        if (destination != null) {
            infoCell.addElement(new Paragraph(
                    "Điểm đến: " + destination,
                    normalFont
            ));
        }

        String handlingClass = pkg.getHandlingClass() != null
                ? pkg.getHandlingClass().name()
                : HandlingClass.STANDARD.name();

        infoCell.addElement(new Paragraph(
                "Loại hàng: " + handlingClass,
                normalFont
        ));

        if (HandlingClass.FRAGILE.name()
                .equals(handlingClass)) {
            infoCell.addElement(new Paragraph(
                    "DỄ VỠ - XỬ LÝ NHẸ TAY",
                    warningFont
            ));
        }

        body.addCell(infoCell);

        PdfPCell bodyCell = new PdfPCell();
        bodyCell.setBorder(Rectangle.NO_BORDER);
        bodyCell.setPadding(0);
        bodyCell.addElement(body);

        content.addCell(bodyCell);

        // ===== KHUNG NGOÀI =====

        PdfPCell outer = new PdfPCell();
        outer.setBorderColor(BORDER_COLOR);
        outer.setBorderWidth(0.8f);
        outer.setPadding(8f);
        outer.setFixedHeight(LABEL_HEIGHT_PT);
        outer.addElement(content);

        return outer;
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

    /**
     * Destination stored on the package (import); otherwise the order's delivery stop address/name; else null.
     */
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

    /**
     * Company of the package itself; legacy packages (created via an order) fall back to the order's company.
     */
    private static String resolveCompanyName(CargoPackage pkg) {
        Company company = pkg.getCompany();
        if (company == null && pkg.getOrder() != null) {
            company = pkg.getOrder().getCompany();
        }
        return company != null && company.getCompanyName() != null && !company.getCompanyName().isBlank()
                ? company.getCompanyName()
                : null;
    }


    private static String formatDimensionsCm(CargoPackage pkg) {
        PackageType type = pkg.getPackageType();

        Integer length = firstNonNull(
                pkg.getActualLength(),
                type != null ? type.getLength() : null
        );

        Integer width = firstNonNull(
                pkg.getActualWidth(),
                type != null ? type.getWidth() : null
        );

        Integer height = firstNonNull(
                pkg.getActualHeight(),
                type != null ? type.getHeight() : null
        );

        if (length == null || width == null || height == null) {
            return null;
        }

        return length + " × " + width + " × " + height;
    }


    private static Integer firstNonNull(Integer first, Integer second) {
        return first != null ? first : second;
    }

    private static String nvl(String s, String fallback) {
        return (s != null && !s.isBlank()) ? s : fallback;
    }
}
