package fu.se184491.loadmaster_be.controller.package_;

import fu.se184491.loadmaster_be.dto.request.LabelExportRequest;
import fu.se184491.loadmaster_be.service.export.PdfLabelExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/packages/export")
@RequiredArgsConstructor
public class PackageExportController {

    private final PdfLabelExportService pdfLabelExportService;

    /**
     * POST /api/packages/export/labels
     * Body: { "packageIds": [1, 2, 3, ...] }
     * Returns a PDF file with QR labels (4 per A4 page, 2×2 grid).
     */
    @PostMapping("/labels")
    @PreAuthorize("hasAuthority('PACKAGE_MANAGE')")
    public ResponseEntity<byte[]> exportLabels(@RequestBody LabelExportRequest request) {
        byte[] pdf = pdfLabelExportService.generateLabelsPdf(request.packageIds());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"package-labels.pdf\"")
                .body(pdf);
    }
}
