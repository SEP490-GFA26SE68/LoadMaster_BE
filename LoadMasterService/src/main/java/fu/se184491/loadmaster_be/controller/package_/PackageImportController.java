package fu.se184491.loadmaster_be.controller.package_;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.response.ImportConfirmResult;
import fu.se184491.loadmaster_be.dto.response.ImportPreviewResult;
import fu.se184491.loadmaster_be.dto.response.PackageDetailResponse;
import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
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
    private final CurrentUserService currentUserService;

    // -------------------------------------------------------------------------
    // Import
    // -------------------------------------------------------------------------

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
//    @PreAuthorize("hasAuthority('PACKAGE_MANAGE')")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ApiResponse<ImportPreviewResult>> preview(@RequestPart("file") MultipartFile file) {
        ImportPreviewResult result = packageImportService.preview(file, currentUserService.getCurrentCompanyId());
        return ResponseEntity.ok(ApiResponse.success("Xem trước import package thành công", result));
    }

    @PostMapping(value = "/import/confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
//    @PreAuthorize("hasAuthority('PACKAGE_MANAGE')")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ApiResponse<ImportConfirmResult>> confirm(@RequestPart("file") MultipartFile file) {
        ImportConfirmResult result = packageImportService.confirm(file,
                currentUserService.getCurrentCompanyId(),
                currentUserService.getCurrentUser().getId());
        return ResponseEntity.ok(ApiResponse.success("Import package thành công", result));
    }

    //PACKAGE_MANAGE
    @GetMapping("/import/template")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<byte[]> downloadTemplate() {
        byte[] template = buildTemplateBytes();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(
                        "Content-Disposition",
                        "attachment; filename=\"mau-nhap-kien-chung.xlsx\""
                )
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



    private byte[] buildTemplateBytes() {
        try (var inputStream = new org.springframework.core.io
                .ClassPathResource("templates/mau-nhap-kien-chung.xlsx")
                .getInputStream()) {

            return inputStream.readAllBytes();

        } catch (java.io.IOException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
