package fu.se184491.loadmaster_be.service.import_.Impl;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.order.OrderStatus;
import fu.se184491.loadmaster_be.dto.request.PackageImportRow;
import fu.se184491.loadmaster_be.dto.response.ImportConfirmResult;
import fu.se184491.loadmaster_be.dto.response.ImportPreviewResult;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackagePackingProfile;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.ImportValidationException;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.cargo.PackageTypeRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.order.OrderRepository;
import fu.se184491.loadmaster_be.service.import_.CsvExcelParserService;
import fu.se184491.loadmaster_be.service.import_.PackageImportService;
import fu.se184491.loadmaster_be.service.import_.PackageImportValidatorService;
import fu.se184491.loadmaster_be.service.qr.QrCodeService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
    private final PackageTypeRepository packageTypeRepository;
    private final EntityManager entityManager;

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
    public ImportConfirmResult confirm(
            MultipartFile file,
            Long companyId,
            Long createdByUserId
    ) {
        List<PackageImportRow> rows = csvExcelParserService.parse(file);

        ImportPreviewResult preview =
                packageImportValidatorService.validate(rows, companyId);

        if (preview.hasErrors()) {
            throw new ImportValidationException(preview);
        }

        Company company = companyRepository.getReferenceById(companyId);

        // Giới hạn tổng số kiện vật lý, không chỉ số dòng Excel.
        long totalQuantity = 0;
        BigDecimal totalWeight = BigDecimal.ZERO;

        Set<String> generatedCodes = new HashSet<>();

        for (PackageImportRow row : preview.validRows()) {
            Integer quantity = row.quantity();

            if (quantity == null || quantity < 1) {
                throw new AppException(ErrorCode.IMPORT_INVALID_QUANTITY);
            }

            totalQuantity += quantity;

            if (totalQuantity > 1000) {
                throw new AppException(ErrorCode.BATCH_SIZE_LIMIT_EXCEEDED);
            }

            totalWeight = totalWeight.add(
                    row.weightKg().multiply(BigDecimal.valueOf(quantity))
            );

            // Kiểm tra trước tất cả mã kiện sẽ phát sinh.
            for (int i = 1; i <= quantity; i++) {
                String code = buildPhysicalPackageCode(
                        row.packageCode(), quantity, i
                );

                if (code.length() > 100) {
                    throw new AppException(ErrorCode.INVALID_INPUT);
                }

                String normalizedCode = code.toUpperCase(Locale.ROOT);

                if (!generatedCodes.add(normalizedCode)) {
                    throw new AppException(
                            ErrorCode.IMPORT_DUPLICATE_GENERATED_CODE
                    );
                }

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
                    throw new AppException(
                            ErrorCode.IMPORT_DUPLICATE_GENERATED_CODE
                    );
                }
            }
        }

        // Giữ nguyên nghiệp vụ hiện tại:
        // một lần import tạo một TransportOrder.
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

            PackageType packageType = null;

            if (row.packageTypeCode() != null
                    && !row.packageTypeCode().isBlank()) {

                packageType = packageTypeRepository
                        .findByCompanyIdAndTypeCodeIgnoreCase(
                                companyId,
                                row.packageTypeCode().trim()
                        )
                        .orElseThrow(() -> new AppException(
                                ErrorCode.IMPORT_PACKAGE_TYPE_NOT_FOUND
                        ));
            }

            HandlingClass handlingClass = HandlingClass.STANDARD;

            if (row.handlingClass() != null
                    && !row.handlingClass().isBlank()) {

                try {
                    handlingClass = HandlingClass.valueOf(
                            row.handlingClass()
                                    .trim()
                                    .toUpperCase(Locale.ROOT)
                    );
                } catch (IllegalArgumentException e) {
                    throw new AppException(
                            ErrorCode.IMPORT_INVALID_HANDLING_CLASS
                    );
                }
            }

            int quantity = row.quantity();

            for (int i = 1; i <= quantity; i++) {

                String physicalCode = buildPhysicalPackageCode(
                        row.packageCode(),
                        quantity,
                        i
                );

                CargoPackage pkg = CargoPackage.builder()
                        .company(company)
                        .order(savedOrder)
                        .packageType(packageType)
                        .packageCode(physicalCode)
                        .packageName(row.packageName())
                        .note(row.note())
                        .qrToken(qrCodeService.generateQrToken())
                        .handlingClass(handlingClass)
                        .destination(
                                normalizeDestination(row.destination())
                        )
                        .actualLength(cmToInt(row.lengthCm()))
                        .actualWidth(cmToInt(row.widthCm()))
                        .actualHeight(cmToInt(row.heightCm()))
                        .actualWeightKg(row.weightKg())
                        .status(PackageStatus.PENDING)
                        .build();

                CargoPackage saved = cargoPackageRepository.save(pkg);

                // Mỗi kiện vật lý có một packing profile riêng.
                CargoPackagePackingProfile profile =
                        CargoPackagePackingProfile.builder()
                                .cargoPackage(saved)
                                .orientation(row.orientation())
                                .uprightOnly(row.uprightOnly())
                                .fragilityLevel(row.fragilityLevel())
                                .stackable(row.stackable())
                                .maxTopLoadKg(row.maxTopLoadKg())
                                .maxLayers(row.maxLayers())
                                .minSupportRatio(row.minSupportRatio())
                                .priority(row.priority())
                                .mustLoad(row.mustLoad())
                                .packingGroup(row.packingGroup())
                                .build();

                entityManager.persist(profile);

                packageIds.add(saved.getId());
            }
        }

        Map<String, Object> auditValues = new HashMap<>();
        auditValues.put("totalImported", packageIds.size());
        auditValues.put("orderId", savedOrder.getId());
        auditValues.put("companyId", companyId);
        auditValues.put("createdByUserId", createdByUserId);

        AuditLog auditLog = AuditLog.builder()
                .actionType("PACKAGE_IMPORT_CONFIRMED")
                .entityName("PACKAGE")
                .entityId("batch")
                .newValues(auditValues)
                .createdAt(Instant.now())
                .build();

        auditLogRepository.save(auditLog);

        log.info(
                "Imported {} physical packages for company {}, order {}",
                packageIds.size(),
                companyId,
                savedOrder.getId()
        );

        return new ImportConfirmResult(
                packageIds.size(),
                packageIds,
                savedOrder.getId()
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

        return code + "-" + String.format(Locale.ROOT, "%03d", index);
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
    /**
     * Excel và database đều sử dụng centimet.
     * Chỉ chấp nhận kích thước nguyên cm vì entity lưu Integer.
     */
    private static Integer cmToInt(BigDecimal cm) {
        if (cm == null) {
            return null;
        }

        try {
            return cm.intValueExact();
        } catch (ArithmeticException e) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }
    }
}
