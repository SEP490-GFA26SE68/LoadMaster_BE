package fu.se184491.loadmaster_be.service.import_.Impl;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.dto.request.PackageImportRow;
import fu.se184491.loadmaster_be.dto.response.ImportConfirmResult;
import fu.se184491.loadmaster_be.dto.response.ImportPreviewResult;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.service.import_.CsvExcelParserService;
import fu.se184491.loadmaster_be.service.import_.PackageImportService;
import fu.se184491.loadmaster_be.service.import_.PackageImportValidatorService;
import fu.se184491.loadmaster_be.service.qr.QrCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PackageImportServiceImpl implements PackageImportService {

    private final CsvExcelParserService csvExcelParserService;
    private final PackageImportValidatorService packageImportValidatorService;
    private final QrCodeService qrCodeService;
    private final CargoPackageRepository cargoPackageRepository;
    private final AuditLogRepository auditLogRepository;

    // -------------------------------------------------------------------------
    // preview — stateless, no DB writes
    // -------------------------------------------------------------------------

    @Override
    public ImportPreviewResult preview(MultipartFile file, Long companyId) {
        List<PackageImportRow> rows = csvExcelParserService.parse(file);
        return packageImportValidatorService.validate(rows, companyId);
    }

    // -------------------------------------------------------------------------
    // confirm — transactional persist
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ImportConfirmResult confirm(MultipartFile file, Long companyId, Long createdByUserId) {
        List<PackageImportRow> rows = csvExcelParserService.parse(file);
        ImportPreviewResult preview = packageImportValidatorService.validate(rows, companyId);

        if (preview.hasErrors()) {
            throw new AppException(ErrorCode.IMPORT_HAS_ERRORS);
        }

        List<Long> packageIds = new ArrayList<>();

        for (PackageImportRow row : preview.validRows()) {
            String qrToken = qrCodeService.generateQrToken();

            HandlingClass handlingClass = HandlingClass.STANDARD;
            if (row.handlingClass() != null && !row.handlingClass().isBlank()) {
                try {
                    handlingClass = HandlingClass.valueOf(row.handlingClass().trim().toUpperCase());
                } catch (IllegalArgumentException ignored) {
                    // validator already caught this; default to STANDARD
                }
            }

            CargoPackage pkg = CargoPackage.builder()
                    .packageCode(row.packageCode())
                    .qrToken(qrToken)
                    .handlingClass(handlingClass)
                    .actualWeightKg(row.weightKg())
                    .status(PackageStatus.PENDING)
                    .build();

            CargoPackage saved = cargoPackageRepository.save(pkg);
            packageIds.add(saved.getId());
        }

        // AuditLog for the full batch
        AuditLog auditLog = AuditLog.builder()
                .actionType("PACKAGE_IMPORT_CONFIRMED")
                .entityName("PACKAGE")
                .entityId("batch")
                .newValues(Map.of(
                        "totalImported", preview.validRows().size(),
                        "companyId", companyId,
                        "createdByUserId", createdByUserId
                ))
                .createdAt(LocalDateTime.now())
                .build();
        auditLogRepository.save(auditLog);

        log.info("Imported {} packages for company {} by user {}",
                packageIds.size(), companyId, createdByUserId);

        return new ImportConfirmResult(packageIds.size(), packageIds);
    }
}
