package fu.se184491.loadmaster_be.service.import_;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.order.OrderStatus;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.dto.request.PackageImportRow;
import fu.se184491.loadmaster_be.dto.response.ImportConfirmResult;
import fu.se184491.loadmaster_be.dto.response.ImportPreviewResult;
import fu.se184491.loadmaster_be.dto.response.RowError;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.ImportValidationException;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.cargo.PackageTypeRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.order.OrderRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.service.import_.Impl.CsvExcelParserServiceImpl;
import fu.se184491.loadmaster_be.service.import_.Impl.PackageImportServiceImpl;
import fu.se184491.loadmaster_be.service.import_.Impl.PackageImportValidatorServiceImpl;
import fu.se184491.loadmaster_be.service.qr.QrCodeService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PackageImportServiceImplTest {

    @Mock
    private CsvExcelParserService csvExcelParserService;

    @Mock
    private PackageImportValidatorService packageImportValidatorService;

    @Mock
    private QrCodeService qrCodeService;

    @Mock
    private CargoPackageRepository cargoPackageRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PackageTypeRepository packageTypeRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private DeliveryRequirementRepository deliveryRequirementRepository;

    @Mock
    private TypedQuery<Long> countQuery;

    @InjectMocks
    private PackageImportServiceImpl packageImportService;

    private Company company;
    private DeliveryRequirement requirement;
    private MockMultipartFile mockFile;

    @BeforeEach
    void setUp() {
        company = Company.builder()
                .id(1L)
                .companyCode("COMP1")
                .companyName("Company One")
                .build();

        requirement = DeliveryRequirement.builder()
                .id(50L)
                .company(company)
                .status(DeliveryRequirementStatus.PENDING)
                .expectedPackageCount(10)
                .packages(new HashSet<>())
                .build();

        mockFile = new MockMultipartFile(
                "file",
                "test.csv",
                "text/csv",
                "packageCode,length,width,height,weight,handlingClass,destination\nPKG-1,60.25,40.50,30.75,12.50,STANDARD,HCM".getBytes()
        );
    }

    @Test
    @DisplayName("confirm import creates a TransportOrder and persists decimal dimensions (up to 2 decimal places) on CargoPackage")
    void confirmImport_createsTransportOrder_andLinksPackagesWithDecimalDimensions() {
        // Given
        Long companyId = 1L;
        Long userId = 99L;
        Long requirementId = 50L;

        PackageImportRow row1 = new PackageImportRow(
                2, "PKG-1",
                new BigDecimal("2.22"), new BigDecimal("40.50"), new BigDecimal("30.75"),
                new BigDecimal("12.50"), "STANDARD", "HCM"
        );
        PackageImportRow row2 = new PackageImportRow(
                3, "PKG-2",
                new BigDecimal("50.10"), new BigDecimal("30.20"), new BigDecimal("20.05"),
                new BigDecimal("7.50"), "FRAGILE", "DN"
        );

        List<PackageImportRow> rows = List.of(row1, row2);
        ImportPreviewResult previewResult = new ImportPreviewResult(2, 2, Collections.emptyList(), Collections.emptyList(), rows);

        when(deliveryRequirementRepository.findByIdAndCompanyId(requirementId, companyId))
                .thenReturn(Optional.of(requirement));
        when(csvExcelParserService.parse(mockFile)).thenReturn(rows);
        when(packageImportValidatorService.validate(rows, companyId)).thenReturn(previewResult);
        when(companyRepository.getReferenceById(companyId)).thenReturn(company);
        when(qrCodeService.generateQrToken()).thenReturn("qr-token-1", "qr-token-2");
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(0L);

        TransportOrder savedOrder = TransportOrder.builder()
                .id(100L)
                .orderCode("ORD-COMP1-20261013-0001")
                .company(company)
                .status(OrderStatus.PENDING)
                .totalWeightKg(new BigDecimal("20.00"))
                .build();
        when(orderRepository.save(any(TransportOrder.class))).thenReturn(savedOrder);

        CargoPackage pkg1 = CargoPackage.builder().id(10L).packageCode("PKG-1").build();
        CargoPackage pkg2 = CargoPackage.builder().id(11L).packageCode("PKG-2").build();
        when(cargoPackageRepository.save(any(CargoPackage.class))).thenReturn(pkg1, pkg2);

        // When
        ImportConfirmResult result = packageImportService.confirm(mockFile, companyId, userId, requirementId);

        // Then
        assertNotNull(result);
        assertEquals(2, result.totalImported());
        assertEquals(List.of(10L, 11L), result.packageIds());
        assertEquals(100L, result.orderId());

        ArgumentCaptor<CargoPackage> pkgCaptor = ArgumentCaptor.forClass(CargoPackage.class);
        verify(cargoPackageRepository, times(2)).save(pkgCaptor.capture());
        List<CargoPackage> capturedPkgs = pkgCaptor.getAllValues();
        assertEquals(2, capturedPkgs.size());

        CargoPackage firstPkg = capturedPkgs.get(0);
        assertEquals(new BigDecimal("2.22"), firstPkg.getActualLength());
        assertEquals(new BigDecimal("40.50"), firstPkg.getActualWidth());
        assertEquals(new BigDecimal("30.75"), firstPkg.getActualHeight());
        assertEquals(new BigDecimal("12.50"), firstPkg.getActualWeightKg());
    }

    @Test
    @DisplayName("confirm import throws exception and does NOT create order if preview has errors")
    void confirmImport_whenHasErrors_doesNotCreateOrder() {
        // Given
        Long companyId = 1L;
        Long userId = 99L;
        Long requirementId = 50L;

        PackageImportRow errorRow = new PackageImportRow(
                2, "INVALID",
                null, null, null, null, null, null
        );
        ImportPreviewResult errorPreview = new ImportPreviewResult(
                1, 0,
                List.of(new RowError(2, "lengthCm", ErrorCode.IMPORT_INVALID_LENGTH)),
                Collections.emptyList()
        );

        when(deliveryRequirementRepository.findByIdAndCompanyId(requirementId, companyId))
                .thenReturn(Optional.of(requirement));
        when(csvExcelParserService.parse(mockFile)).thenReturn(List.of(errorRow));
        when(packageImportValidatorService.validate(any(), eq(companyId))).thenReturn(errorPreview);

        // When & Then
        ImportValidationException exception = assertThrows(ImportValidationException.class, () ->
                packageImportService.confirm(mockFile, companyId, userId, requirementId)
        );
        assertEquals(ErrorCode.IMPORT_HAS_ERRORS, exception.getErrorCode());
        assertNotNull(exception.getPreviewResult());
        assertEquals(1, exception.getPreviewResult().errors().size());
        assertEquals("lengthCm", exception.getPreviewResult().errors().get(0).field());

        verify(orderRepository, never()).save(any());
        verify(cargoPackageRepository, never()).save(any());
    }

    @Test
    @DisplayName("Excel parser & validator support comma/dot decimals up to 2 places and reject >2 decimal places or invalid numbers with row-level ErrorCode")
    void excelParserAndValidator_decimalScaleAndFormatValidation() throws Exception {
        CsvExcelParserServiceImpl realParser = new CsvExcelParserServiceImpl();
        PackageImportValidatorServiceImpl realValidator = new PackageImportValidatorServiceImpl(
                cargoPackageRepository, packageTypeRepository, entityManager
        );
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(0L);

        // Build an in-memory .xlsx file with 3 data rows:
        // Row 2 (valid comma & dot <= 2 decimal places): length="2,22", width="3.45", height="10,5", weight="5,25"
        // Row 3 (invalid > 2 decimal places): length="2,225", width="3.123", height="4.999", weight="5.555"
        // Row 4 (invalid non-numeric text): length="abc", width="20", height="30", weight="10"
        byte[] xlsxBytes;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Packages");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("packageCode");
            header.createCell(1).setCellValue("lengthCm");
            header.createCell(2).setCellValue("widthCm");
            header.createCell(3).setCellValue("heightCm");
            header.createCell(4).setCellValue("weightKg");
            header.createCell(5).setCellValue("quantity");
            header.createCell(6).setCellValue("destination");
            header.createCell(7).setCellValue("handlingClass");

            Row r2 = sheet.createRow(1); // Excel row 2
            r2.createCell(0).setCellValue("PKG-VALID");
            r2.createCell(1).setCellValue("2,22");
            r2.createCell(2).setCellValue("3.45");
            r2.createCell(3).setCellValue("10,5");
            r2.createCell(4).setCellValue("5,25");
            r2.createCell(5).setCellValue("1");
            r2.createCell(6).setCellValue("Ha Noi");
            r2.createCell(7).setCellValue("STANDARD");

            Row r3 = sheet.createRow(2); // Excel row 3 (> 2 decimal places)
            r3.createCell(0).setCellValue("PKG-SCALE-ERR");
            r3.createCell(1).setCellValue("2,225");
            r3.createCell(2).setCellValue("3.123");
            r3.createCell(3).setCellValue("4.999");
            r3.createCell(4).setCellValue("5.555");
            r3.createCell(5).setCellValue("1");
            r3.createCell(6).setCellValue("Da Nang");
            r3.createCell(7).setCellValue("STANDARD");

            Row r4 = sheet.createRow(3); // Excel row 4 (invalid number text)
            r4.createCell(0).setCellValue("PKG-NAN-ERR");
            r4.createCell(1).setCellValue("abc");
            r4.createCell(2).setCellValue("20");
            r4.createCell(3).setCellValue("30");
            r4.createCell(4).setCellValue("10");
            r4.createCell(5).setCellValue("1");
            r4.createCell(6).setCellValue("HCM");
            r4.createCell(7).setCellValue("STANDARD");

            wb.write(out);
            xlsxBytes = out.toByteArray();
        }

        MockMultipartFile xlsxFile = new MockMultipartFile(
                "file",
                "packages.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxBytes
        );

        List<PackageImportRow> parsedRows = realParser.parse(xlsxFile);
        assertEquals(3, parsedRows.size());
        assertEquals(new BigDecimal("2.22"), parsedRows.get(0).lengthCm());
        assertEquals(new BigDecimal("3.45"), parsedRows.get(0).widthCm());
        assertEquals(new BigDecimal("10.5"), parsedRows.get(0).heightCm());
        assertEquals(new BigDecimal("5.25"), parsedRows.get(0).weightKg());

        ImportPreviewResult validation = realValidator.validate(parsedRows, 1L);
        assertEquals(3, validation.totalRows());
        assertEquals(1, validation.validCount());
        assertEquals("PKG-VALID", validation.validRows().get(0).packageCode());

        // Row 3 should have 4 IMPORT_DECIMAL_SCALE_EXCEEDED errors (lengthCm, widthCm, heightCm, weightKg)
        List<RowError> row3Errors = validation.errors().stream()
                .filter(e -> e.rowIndex() == 3)
                .toList();
        assertEquals(4, row3Errors.size());
        assertTrue(row3Errors.stream().allMatch(e -> e.errorCode() == ErrorCode.IMPORT_DECIMAL_SCALE_EXCEEDED));

        // Row 4 should have IMPORT_INVALID_NUMBER_FORMAT on lengthCm
        List<RowError> row4Errors = validation.errors().stream()
                .filter(e -> e.rowIndex() == 4)
                .toList();
        assertEquals(1, row4Errors.size());
        assertEquals("lengthCm", row4Errors.get(0).field());
        assertEquals(ErrorCode.IMPORT_INVALID_NUMBER_FORMAT, row4Errors.get(0).errorCode());
    }

    @Test
    @DisplayName("Validator reports clear row error when cumulative physical packages exceed 1000 (e.g. 1001 packages)")
    void validator_whenTotalPhysicalPackagesReaches1001_reportsBatchSizeLimitExceededOnExactRow() {
        PackageImportValidatorServiceImpl realValidator = new PackageImportValidatorServiceImpl(
                cargoPackageRepository, packageTypeRepository, entityManager
        );
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(0L);

        PackageImportRow row2 = new PackageImportRow(
                2, "PKG-BATCH-A", "Kien A",
                new BigDecimal("10.00"), new BigDecimal("10.00"), new BigDecimal("10.00"),
                new BigDecimal("1.00"), 500, "Ha Noi",
                null, null, null, null, null, null, null, null, null, null, null, "STANDARD", null
        );
        PackageImportRow row3 = new PackageImportRow(
                3, "PKG-BATCH-B", "Kien B",
                new BigDecimal("10.00"), new BigDecimal("10.00"), new BigDecimal("10.00"),
                new BigDecimal("1.00"), 501, "HCM",
                null, null, null, null, null, null, null, null, null, null, null, "STANDARD", null
        );

        ImportPreviewResult result = realValidator.validate(List.of(row2, row3), 1L);
        assertEquals(2, result.totalRows());
        assertEquals(1, result.validCount());
        assertEquals(1, result.errors().size());

        RowError err = result.errors().get(0);
        assertEquals(3, err.rowIndex());
        assertEquals("quantity", err.field());
        assertEquals(ErrorCode.BATCH_SIZE_LIMIT_EXCEEDED, err.errorCode());
        assertTrue(err.message().contains("Tổng số kiện vật lý vượt quá 1000"));
    }

    @Test
    @DisplayName("Excel parser automatically sets quantity=1 when Excel file does not contain a quantity column (7-column format)")
    void excelParser_whenQuantityColumnMissing_defaultsQuantityToOne() throws Exception {
        CsvExcelParserServiceImpl realParser = new CsvExcelParserServiceImpl();
        PackageImportValidatorServiceImpl realValidator = new PackageImportValidatorServiceImpl(
                cargoPackageRepository, packageTypeRepository, entityManager
        );
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(0L);

        byte[] xlsxBytes;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Packages");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("packageCode");
            header.createCell(1).setCellValue("lengthCm");
            header.createCell(2).setCellValue("widthCm");
            header.createCell(3).setCellValue("heightCm");
            header.createCell(4).setCellValue("weightKg");
            header.createCell(5).setCellValue("handlingClass");
            header.createCell(6).setCellValue("destination");

            Row r2 = sheet.createRow(1);
            r2.createCell(0).setCellValue("PM-DEMO-0001");
            r2.createCell(1).setCellValue(131.49);
            r2.createCell(2).setCellValue(14.75);
            r2.createCell(3).setCellValue(48.5);
            r2.createCell(4).setCellValue(112.38);
            r2.createCell(5).setCellValue("STANDARD");
            r2.createCell(6).setCellValue("Kho Lien Chieu, Da Nang");

            wb.write(out);
            xlsxBytes = out.toByteArray();
        }

        MockMultipartFile xlsxFile = new MockMultipartFile(
                "file",
                "packages_import_1000_test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxBytes
        );

        List<PackageImportRow> parsedRows = realParser.parse(xlsxFile);
        assertEquals(1, parsedRows.size());
        assertEquals(1, parsedRows.get(0).quantity());

        ImportPreviewResult validation = realValidator.validate(parsedRows, 1L);
        assertEquals(1, validation.totalRows());
        assertEquals(1, validation.validCount());
        assertTrue(validation.errors().isEmpty());
    }

    @Test
    @DisplayName("Template .xlsx (templates/mau-nhap-kien-chung.xlsx) uses English field headers and parses cleanly with zero errors")
    void templateXlsx_hasEnglishFieldHeadersAndParsesCleanly() throws Exception {
        byte[] templateBytes;
        try (var is = new org.springframework.core.io.ClassPathResource("templates/mau-nhap-kien-chung.xlsx").getInputStream()) {
            templateBytes = is.readAllBytes();
        }

        List<String> expectedHeaders = List.of(
                "packageCode", "packageName", "lengthCm", "widthCm", "heightCm",
                "weightKg", "quantity", "destination", "orientation", "uprightOnly",
                "fragilityLevel", "stackable", "maxTopLoadKg", "maxLayers", "minSupportRatio",
                "priority", "mustLoad", "group", "note", "handlingClass", "packageTypeCode"
        );

        try (Workbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(templateBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            assertNotNull(headerRow);
            for (int i = 0; i < expectedHeaders.size(); i++) {
                assertNotNull(headerRow.getCell(i), "Header cell " + i + " should not be null");
                assertEquals(expectedHeaders.get(i), headerRow.getCell(i).getStringCellValue());
            }
        }

        CsvExcelParserServiceImpl realParser = new CsvExcelParserServiceImpl();
        PackageImportValidatorServiceImpl realValidator = new PackageImportValidatorServiceImpl(
                cargoPackageRepository, packageTypeRepository, entityManager
        );
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(0L);

        MockMultipartFile templateFile = new MockMultipartFile(
                "file",
                "mau-nhap-kien-chung.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                templateBytes
        );

        List<PackageImportRow> parsedRows = realParser.parse(templateFile);
        assertTrue(parsedRows.size() >= 2);

        ImportPreviewResult validation = realValidator.validate(parsedRows, 1L);
        assertTrue(validation.errors().isEmpty(), "Template rows should have 0 validation errors");
        assertEquals(parsedRows.size(), validation.validCount());
    }
}
