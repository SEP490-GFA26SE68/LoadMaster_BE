package fu.se184491.loadmaster_be.service.import_.Impl;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.order.OrderStatus;
import fu.se184491.loadmaster_be.dto.request.PackageImportRow;
import fu.se184491.loadmaster_be.dto.response.ImportConfirmResult;
import fu.se184491.loadmaster_be.dto.response.ImportPreviewResult;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.ImportValidationException;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.order.OrderRepository;
import fu.se184491.loadmaster_be.service.import_.CsvExcelParserService;
import fu.se184491.loadmaster_be.service.import_.PackageImportService;
import fu.se184491.loadmaster_be.service.import_.PackageImportValidatorService;
import fu.se184491.loadmaster_be.service.qr.QrCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class PackageImportServiceImpl implements PackageImportService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CsvExcelParserService csvExcelParserService;
    private final PackageImportValidatorService packageImportValidatorService;
    private final QrCodeService qrCodeService;
    private final CargoPackageRepository cargoPackageRepository;
    private final AuditLogRepository auditLogRepository;
    private final CompanyRepository companyRepository;
    private final OrderRepository orderRepository;

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
            throw new ImportValidationException(preview);
        }

        Company company = companyRepository.getReferenceById(companyId);

        // Calculate total weight of valid packages for the order
        BigDecimal totalWeight = preview.validRows().stream()
                .map(PackageImportRow::weightKg)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Auto-create 1 TransportOrder for the whole imported batch
        String orderCode = generateOrderCode(company);
        TransportOrder order = TransportOrder.builder()
                .company(company)
                .orderCode(orderCode)
                .status(OrderStatus.PENDING)
                .totalWeightKg(totalWeight)
                .build();
        TransportOrder savedOrder = orderRepository.save(order);

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
                    .order(savedOrder)
                    .packageCode(row.packageCode())
                    .qrToken(qrToken)
                    .handlingClass(handlingClass)
                    .company(company)
                    .destination(normalizeDestination(row.destination()))
                    .actualLength(cmToMm(row.lengthCm()))
                    .actualWidth(cmToMm(row.widthCm()))
                    .actualHeight(cmToMm(row.heightCm()))
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
                        "orderId", savedOrder.getId(),
                        "companyId", companyId,
                        "createdByUserId", createdByUserId
                ))
                .createdAt(LocalDateTime.now())
                .build();
        auditLogRepository.save(auditLog);

        log.info("Imported {} packages for company {} by user {}, created order {}",
                packageIds.size(), companyId, createdByUserId, savedOrder.getId());

        return new ImportConfirmResult(packageIds.size(), packageIds, savedOrder.getId());
    }

    private String generateOrderCode(Company company) {
        String companyCode = company.getCompanyCode() != null ? company.getCompanyCode() : "COMP";
        String dateStr = LocalDate.now().format(DATE_FORMATTER);
        String prefix = "ORD-" + companyCode + "-" + dateStr + "-";

        long count = orderRepository.countByCompanyIdAndOrderCodeStartingWith(company.getId(), prefix);
        long seq = count + 1;
        String orderCode = String.format("%s%04d", prefix, seq);

        while (orderRepository.existsByOrderCode(orderCode)) {
            seq++;
            orderCode = String.format("%s%04d", prefix, seq);
        }
        return orderCode;
    }

    private static String normalizeDestination(String destination) {
        return destination == null || destination.isBlank() ? null : destination.trim();
    }

    /** Import files are in centimetres; packages store millimetres. */
    private static Integer cmToMm(BigDecimal cm) {
        return cm == null ? null : cm.multiply(BigDecimal.TEN).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
