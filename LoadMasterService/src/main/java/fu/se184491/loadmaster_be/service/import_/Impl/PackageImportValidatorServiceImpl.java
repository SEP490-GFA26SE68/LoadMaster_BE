package fu.se184491.loadmaster_be.service.import_.Impl;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.dto.request.PackageImportRow;
import fu.se184491.loadmaster_be.dto.response.ImportPreviewResult;
import fu.se184491.loadmaster_be.dto.response.RowError;
import fu.se184491.loadmaster_be.dto.response.RowWarning;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.service.import_.PackageImportValidatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PackageImportValidatorServiceImpl implements PackageImportValidatorService {

    private final CargoPackageRepository cargoPackageRepository;

    @Override
    public ImportPreviewResult validate(List<PackageImportRow> rows, Long companyId) {
        if (rows == null || rows.isEmpty()) {
            return new ImportPreviewResult(0, 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        List<RowError> errors = new ArrayList<>();
        List<RowWarning> warnings = new ArrayList<>();
        List<PackageImportRow> validRows = new ArrayList<>();

        // 1. Detect duplicates within the same batch
        Map<String, Integer> seenCodes = new HashMap<>();
        Set<Integer> duplicateRowIndices = new HashSet<>();

        for (PackageImportRow row : rows) {
            if (row.packageCode() != null && !row.packageCode().trim().isEmpty()) {
                String code = row.packageCode().trim();
                if (seenCodes.containsKey(code)) {
                    int originalRowIndex = seenCodes.get(code);
                    duplicateRowIndices.add(row.rowIndex());
                    errors.add(new RowError(
                            row.rowIndex(),
                            "packageCode",
                            ErrorCode.IMPORT_DUPLICATE_CODE,
                            "trùng với dòng " + originalRowIndex
                    ));
                } else {
                    seenCodes.put(code, row.rowIndex());
                }
            }
        }

        // 2. Validate fields for each row
        for (PackageImportRow row : rows) {
            boolean hasError = duplicateRowIndices.contains(row.rowIndex());

            // Validate dimensions: length, width, height > 0
            if (row.lengthCm() == null || row.lengthCm().compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(new RowError(row.rowIndex(), "lengthCm", ErrorCode.IMPORT_INVALID_LENGTH));
                hasError = true;
            }

            if (row.widthCm() == null || row.widthCm().compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(new RowError(row.rowIndex(), "widthCm", ErrorCode.IMPORT_INVALID_WIDTH));
                hasError = true;
            }

            if (row.heightCm() == null || row.heightCm().compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(new RowError(row.rowIndex(), "heightCm", ErrorCode.IMPORT_INVALID_HEIGHT));
                hasError = true;
            }

            // Validate weight: weightKg > 0
            if (row.weightKg() == null || row.weightKg().compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(new RowError(row.rowIndex(), "weightKg", ErrorCode.IMPORT_INVALID_WEIGHT));
                hasError = true;
            }

            // Validate handlingClass: must belong to HandlingClass enum
            if (row.handlingClass() != null && !row.handlingClass().trim().isEmpty()) {
                try {
                    HandlingClass.valueOf(row.handlingClass().trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    errors.add(new RowError(
                            row.rowIndex(),
                            "handlingClass",
                            ErrorCode.IMPORT_INVALID_HANDLING_CLASS,
                            "'" + row.handlingClass() + "'"
                    ));
                    hasError = true;
                }
            }

            // Validate destination: must not be blank
            if (row.destination() == null || row.destination().trim().isEmpty()) {
                errors.add(new RowError(row.rowIndex(), "destination", ErrorCode.IMPORT_BLANK_DESTINATION));
                hasError = true;
            }

            // Check if packageCode already exists in DB (produces WARNING, does NOT reject row)
            if (companyId != null && row.packageCode() != null && !row.packageCode().trim().isEmpty()) {
                String code = row.packageCode().trim();
                if (cargoPackageRepository.existsByOrderCompanyIdAndPackageCode(companyId, code)) {
                    warnings.add(new RowWarning(
                            row.rowIndex(),
                            "packageCode",
                            ErrorCode.IMPORT_EXISTING_CODE,
                            "'" + code + "'"
                    ));
                }
            }

            if (!hasError) {
                validRows.add(row);
            }
        }

        return new ImportPreviewResult(
                rows.size(),
                validRows.size(),
                errors,
                warnings,
                validRows
        );
    }
}
