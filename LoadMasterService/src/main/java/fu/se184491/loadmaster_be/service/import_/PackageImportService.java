package fu.se184491.loadmaster_be.service.import_;

import fu.se184491.loadmaster_be.dto.response.ImportConfirmResult;
import fu.se184491.loadmaster_be.dto.response.ImportPreviewResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * Orchestrates the full package-import flow: parse → validate → persist → QR.
 */
public interface PackageImportService {

    /**
     * Stateless preview: parse + validate, no DB writes.
     *
     * @param file      uploaded CSV/Excel
     * @param companyId owning company
     * @return validation preview with errors, warnings, and valid rows
     */
    ImportPreviewResult preview(MultipartFile file, Long companyId);

    /**
     * Confirm import: parse + validate; if errors → throw {@code AppException(IMPORT_HAS_ERRORS)};
     * otherwise persist all valid rows in a single transaction, generate QR tokens, write AuditLog.
     *
     * @param file            uploaded CSV/Excel
     * @param companyId       owning company
     * @param createdByUserId ID of the user confirming the import
     * @return result containing total imported count and new package IDs
     */
    ImportConfirmResult confirm(MultipartFile file, Long companyId, Long createdByUserId);
}
