package fu.se184491.loadmaster_be.dto.response;

import fu.se184491.loadmaster_be.dto.request.PackageImportRow;

import java.util.Collections;
import java.util.List;

/**
 * Preview result returned after validating a batch of imported packages.
 *
 * @param totalRows  Total number of data rows evaluated
 * @param validCount Number of rows that passed all validation rules
 * @param errors     List of errors detected across all rows
 * @param warnings   List of non-blocking warnings (e.g. existing package codes)
 * @param validRows  List of valid PackageImportRow instances eligible for persisting
 */
public record ImportPreviewResult(
        int totalRows,
        int validCount,
        List<RowError> errors,
        List<RowWarning> warnings,
        List<PackageImportRow> validRows
) {
    public ImportPreviewResult(int totalRows, int validCount, List<RowError> errors, List<RowWarning> warnings) {
        this(totalRows, validCount, errors, warnings, Collections.emptyList());
    }

    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }
}
