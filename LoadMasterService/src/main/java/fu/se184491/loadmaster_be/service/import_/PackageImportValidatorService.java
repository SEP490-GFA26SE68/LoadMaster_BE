package fu.se184491.loadmaster_be.service.import_;

import fu.se184491.loadmaster_be.dto.request.PackageImportRow;
import fu.se184491.loadmaster_be.dto.response.ImportPreviewResult;

import java.util.List;

public interface PackageImportValidatorService {

    /**
     * Validates each row in the imported batch, collecting all errors and non-blocking warnings.
     *
     * @param rows      List of raw parsed rows
     * @param companyId Tenant company ID for checking duplicates in DB
     * @return ImportPreviewResult with summary counts, errors, warnings, and valid rows
     */
    ImportPreviewResult validate(List<PackageImportRow> rows, Long companyId);
}
