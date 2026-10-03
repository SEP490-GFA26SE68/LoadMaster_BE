package fu.se184491.loadmaster_be.controller.package_;

import fu.se184491.loadmaster_be.dto.response.ImportConfirmResult;
import fu.se184491.loadmaster_be.dto.response.ImportPreviewResult;
import fu.se184491.loadmaster_be.dto.response.PackageDetailResponse;
import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.import_.PackageImportService;
import fu.se184491.loadmaster_be.service.qr.QrCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/packages")
@RequiredArgsConstructor
public class PackageImportController {

    private final PackageImportService packageImportService;
    private final QrCodeService qrCodeService;

    // TODO: Extract from Security Context
    private Long getCurrentCompanyId() { return 1L; }
    private Long getCurrentUserId()    { return 1L; }

    // -------------------------------------------------------------------------
    // Import
    // -------------------------------------------------------------------------

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
//    @PreAuthorize("hasAuthority('PACKAGE_MANAGE')")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ImportPreviewResult> preview(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(packageImportService.preview(file, getCurrentCompanyId()));
    }

    @PostMapping(value = "/import/confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
//    @PreAuthorize("hasAuthority('PACKAGE_MANAGE')")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ImportConfirmResult> confirm(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(
                packageImportService.confirm(file, getCurrentCompanyId(), getCurrentUserId()));
    }

    //PACKAGE_MANAGE
    @GetMapping("/import/template")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<byte[]> downloadTemplate() {
        byte[] template = buildTemplateBytes();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header("Content-Disposition", "attachment; filename=\"packages_template.xlsx\"")
                .body(template);
    }

    // -------------------------------------------------------------------------
    // QR
    // -------------------------------------------------------------------------

    @GetMapping(value = "/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_DISPATCHER', 'WAREHOUSE_WORKER')")
    public ResponseEntity<byte[]> getQrImage(@PathVariable Long id) {
        CargoPackage pkg = qrCodeService.findById(id);
        byte[] png = qrCodeService.generateQrPng(pkg.getQrToken());
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

//    @GetMapping("/scan/{qrToken}")
//    @PreAuthorize("hasAnyAuthority('PACKAGE_MANAGE', 'WAREHOUSE_WORKER')")
//    public ResponseEntity<PackageDetailResponse> scanPackage(@PathVariable String qrToken) {
//        CargoPackage pkg = qrCodeService.lookupByToken(qrToken);
//        return ResponseEntity.ok(toDetailResponse(pkg));
//    }

    // -------------------------------------------------------------------------
    // Package detail
    // -------------------------------------------------------------------------

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DISPATCHER', 'WAREHOUSE_WORKER')")
    public ResponseEntity<PackageDetailResponse> getById(@PathVariable Long id) {
        CargoPackage pkg = qrCodeService.findById(id);
        return ResponseEntity.ok(toDetailResponse(pkg));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PackageDetailResponse toDetailResponse(CargoPackage pkg) {
        return PackageDetailResponse.builder()
                .id(pkg.getId())
                .packageCode(pkg.getPackageCode())
                .qrToken(pkg.getQrToken())
                .handlingClass(pkg.getHandlingClass() != null
                        ? pkg.getHandlingClass() : HandlingClass.STANDARD)
                .actualWeightKg(pkg.getActualWeightKg())
                .status(pkg.getStatus())
                .orderId(pkg.getOrder() != null ? pkg.getOrder().getId() : null)
                .build();
    }

    private byte[] buildTemplateBytes() {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("packages");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            String[] cols = {"packageCode", "lengthMm", "widthMm", "heightMm",
                             "weightKg", "handlingClass", "destination"};
            for (int i = 0; i < cols.length; i++) {
                header.createCell(i).setCellValue(cols[i]);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
