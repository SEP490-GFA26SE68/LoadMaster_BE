package fu.se184491.loadmaster_be.service.import_.Impl;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.dto.request.PackageImportRow;
import fu.se184491.loadmaster_be.dto.response.ImportPreviewResult;
import fu.se184491.loadmaster_be.dto.response.RowError;
import fu.se184491.loadmaster_be.dto.response.RowWarning;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.cargo.PackageTypeRepository;
import fu.se184491.loadmaster_be.service.import_.PackageImportValidatorService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PackageImportValidatorServiceImpl implements PackageImportValidatorService {

    private final CargoPackageRepository cargoPackageRepository;
    private final PackageTypeRepository packageTypeRepository;
    private final EntityManager entityManager;
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
                String code = row.packageCode().trim().toUpperCase(Locale.ROOT);
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

            // Validate package code
            if (row.packageCode() == null
                    || row.packageCode().isBlank()) {
                errors.add(new RowError(
                        row.rowIndex(),
                        "packageCode",
                        ErrorCode.IMPORT_BLANK_PACKAGE_CODE
                ));
                hasError = true;
            }

            // Validate package name length
            if (row.packageName() != null
                    && row.packageName().length() > 150) {
                errors.add(new RowError(
                        row.rowIndex(),
                        "packageName",
                        ErrorCode.IMPORT_INVALID_PACKAGE_NAME
                ));
                hasError = true;
            }

            // Quantity must be a positive integer
            if (row.quantity() == null
                    || row.quantity() < 1) {
                errors.add(new RowError(
                        row.rowIndex(),
                        "quantity",
                        ErrorCode.IMPORT_INVALID_QUANTITY
                ));
                hasError = true;
            }

            // Fragility level cannot be negative
            if (row.fragilityLevel() != null
                    && row.fragilityLevel() < 0) {
                errors.add(new RowError(
                        row.rowIndex(),
                        "fragilityLevel",
                        ErrorCode.IMPORT_INVALID_FRAGILITY_LEVEL
                ));
                hasError = true;
            }

            // Maximum top load cannot be negative
            if (row.maxTopLoadKg() != null
                    && row.maxTopLoadKg()
                    .compareTo(BigDecimal.ZERO) < 0) {
                errors.add(new RowError(
                        row.rowIndex(),
                        "maxTopLoadKg",
                        ErrorCode.IMPORT_INVALID_MAX_TOP_LOAD
                ));
                hasError = true;
            }

            // Maximum layers must be >= 1 when provided
            if (row.maxLayers() != null
                    && row.maxLayers() < 1) {
                errors.add(new RowError(
                        row.rowIndex(),
                        "maxLayers",
                        ErrorCode.IMPORT_INVALID_MAX_LAYERS
                ));
                hasError = true;
            }

            // Support ratio must be between 0 and 1
            if (row.minSupportRatio() != null
                    && (row.minSupportRatio()
                    .compareTo(BigDecimal.ZERO) < 0
                    || row.minSupportRatio()
                    .compareTo(BigDecimal.ONE) > 0)) {
                errors.add(new RowError(
                        row.rowIndex(),
                        "minSupportRatio",
                        ErrorCode.IMPORT_INVALID_MIN_SUPPORT_RATIO
                ));
                hasError = true;
            }

            // Packing group length matches the entity
            if (row.packingGroup() != null
                    && row.packingGroup().length() > 100) {
                errors.add(new RowError(
                        row.rowIndex(),
                        "packingGroup",
                        ErrorCode.IMPORT_INVALID_PACKING_GROUP
                ));
                hasError = true;
            }

            // Orientation length matches the entity.
            // Allowed orientation values will be checked later.
            if (row.orientation() != null
                    && row.orientation().length() > 100) {
                errors.add(new RowError(
                        row.rowIndex(),
                        "orientation",
                        ErrorCode.IMPORT_INVALID_ORIENTATION
                ));
                hasError = true;
            }
            if (row.packageCode() != null
                    && row.packageCode().length() > 100) {
                errors.add(new RowError(
                        row.rowIndex(),
                        "packageCode",
                        ErrorCode.INVALID_INPUT,
                        "Mã kiện vượt quá 100 ký tự"
                ));
                hasError = true;
            }

            if (row.destination() != null
                    && row.destination().length() > 255) {
                errors.add(new RowError(
                        row.rowIndex(),
                        "destination",
                        ErrorCode.INVALID_INPUT,
                        "Điểm đến vượt quá 255 ký tự"
                ));
                hasError = true;
            }

            // Validate PackageType within the current company.
            if (row.packageTypeCode() != null
                    && !row.packageTypeCode().isBlank()) {

                String typeCode = row.packageTypeCode().trim();

                boolean exists = companyId != null
                        && packageTypeRepository
                        .existsByCompanyIdAndTypeCodeIgnoreCase(
                                companyId,
                                typeCode
                        );

                if (!exists) {
                    errors.add(new RowError(
                            row.rowIndex(),
                            "packageTypeCode",
                            ErrorCode.IMPORT_PACKAGE_TYPE_NOT_FOUND,
                            "'" + typeCode + "'"
                    ));
                    hasError = true;
                }
            }

            if (!hasError) {
                validRows.add(row);
            }
        }


        // 3. Validate generated physical package codes.
        // Must follow the same naming convention as Confirm Import.
        Map<String, Integer> generatedCodeOwners = new HashMap<>();
        Set<Integer> generatedCodeErrorRows = new HashSet<>();

        long totalPhysicalPackages = 0;
        final long maxPhysicalPackages = 1000;

        for (PackageImportRow row : rows) {

            Integer quantity = row.quantity();

            if (quantity == null || quantity < 1
                    || row.packageCode() == null
                    || row.packageCode().isBlank()) {
                continue;
            }

            // Count physical packages, not Excel rows.
            totalPhysicalPackages += quantity;

            // Avoid unnecessarily generating huge numbers of codes.
            if (totalPhysicalPackages > maxPhysicalPackages) {
                errors.add(new RowError(
                        row.rowIndex(),
                        "quantity",
                        ErrorCode.BATCH_SIZE_LIMIT_EXCEEDED,
                        "Tổng số kiện vật lý vượt quá 1000"
                ));
                generatedCodeErrorRows.add(row.rowIndex());
                continue;
            }

            for (int i = 1; i <= quantity; i++) {

                String generatedCode = buildPhysicalPackageCode(
                        row.packageCode(),
                        quantity,
                        i
                );

                if (generatedCode.length() > 100) {
                    errors.add(new RowError(
                            row.rowIndex(),
                            "packageCode",
                            ErrorCode.INVALID_INPUT,
                            "Mã kiện sau khi sinh vượt quá 100 ký tự"
                    ));
                    generatedCodeErrorRows.add(row.rowIndex());
                    break;
                }

                String normalizedCode = generatedCode
                        .toUpperCase(Locale.ROOT);

                // Duplicate generated code within this import file.
                Integer originalRow = generatedCodeOwners.putIfAbsent(
                        normalizedCode,
                        row.rowIndex()
                );

                if (originalRow != null) {
                    errors.add(new RowError(
                            row.rowIndex(),
                            "packageCode",
                            ErrorCode.IMPORT_DUPLICATE_GENERATED_CODE,
                            "'" + generatedCode
                                    + "' trùng với dòng " + originalRow
                    ));
                    generatedCodeErrorRows.add(row.rowIndex());
                    continue;
                }

                // Duplicate generated code in this company's DB.
                if (companyId != null) {
                    Long existingCount = entityManager.createQuery(
                                    """
                                    SELECT COUNT(p)
                                    FROM CargoPackage p
                                    WHERE p.company.id = :companyId
                                      AND UPPER(p.packageCode) = :code
                                    """,
                                    Long.class
                            )
                            .setParameter("companyId", companyId)
                            .setParameter("code", normalizedCode)
                            .getSingleResult();

                    if (existingCount > 0) {
                        errors.add(new RowError(
                                row.rowIndex(),
                                "packageCode",
                                ErrorCode.IMPORT_DUPLICATE_GENERATED_CODE,
                                "'" + generatedCode
                                        + "' đã tồn tại trong hệ thống"
                        ));
                        generatedCodeErrorRows.add(row.rowIndex());
                    }
                }
            }
        }

        // Remove rows with generated-code errors from validRows.
        validRows.removeIf(
                row -> generatedCodeErrorRows.contains(row.rowIndex())
        );


        return new ImportPreviewResult(
                rows.size(),
                validRows.size(),
                errors,
                warnings,
                validRows
        );
    }

    private static String buildPhysicalPackageCode(
            String baseCode,
            int quantity,
            int index
    ) {
        String code = baseCode.trim();

        if (quantity == 1) {
            return code;
        }

        return code + "-" + String.format(
                Locale.ROOT,
                "%03d",
                index
        );
    }
}
