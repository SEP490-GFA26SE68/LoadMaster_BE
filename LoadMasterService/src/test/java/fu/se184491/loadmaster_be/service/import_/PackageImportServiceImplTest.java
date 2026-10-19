package fu.se184491.loadmaster_be.service.import_;

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
import fu.se184491.loadmaster_be.service.import_.Impl.PackageImportServiceImpl;
import fu.se184491.loadmaster_be.service.qr.QrCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    private PackageImportServiceImpl packageImportService;

    private Company company;
    private MockMultipartFile mockFile;

    @BeforeEach
    void setUp() {
        company = Company.builder()
                .id(1L)
                .companyCode("COMP1")
                .companyName("Company One")
                .build();

        mockFile = new MockMultipartFile(
                "file",
                "test.csv",
                "text/csv",
                "packageCode,length,width,height,weight,handlingClass,destination\nPKG-1,60,40,30,12.5,STANDARD,HCM".getBytes()
        );
    }

    @Test
    @DisplayName("confirm import creates a TransportOrder and links all packages to it")
    void confirmImport_createsTransportOrder_andLinksPackages() {
        // Given
        Long companyId = 1L;
        Long userId = 99L;

        PackageImportRow row1 = new PackageImportRow(
                1, "PKG-1",
                new BigDecimal("60"), new BigDecimal("40"), new BigDecimal("30"),
                new BigDecimal("12.50"), "STANDARD", "HCM"
        );
        PackageImportRow row2 = new PackageImportRow(
                2, "PKG-2",
                new BigDecimal("50"), new BigDecimal("30"), new BigDecimal("20"),
                new BigDecimal("7.50"), "FRAGILE", "DN"
        );

        List<PackageImportRow> rows = List.of(row1, row2);
        ImportPreviewResult previewResult = new ImportPreviewResult(2, 2, Collections.emptyList(), Collections.emptyList(), rows);

        when(csvExcelParserService.parse(mockFile)).thenReturn(rows);
        when(packageImportValidatorService.validate(rows, companyId)).thenReturn(previewResult);
        when(companyRepository.getReferenceById(companyId)).thenReturn(company);
        when(qrCodeService.generateQrToken()).thenReturn("qr-token-1", "qr-token-2");

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
        ImportConfirmResult result = packageImportService.confirm(mockFile, companyId, userId, null);

        // Then
        assertNotNull(result);
        assertEquals(2, result.totalImported());
        assertEquals(List.of(10L, 11L), result.packageIds());
        assertEquals(100L, result.orderId());

        // Verify TransportOrder creation
        ArgumentCaptor<TransportOrder> orderCaptor = ArgumentCaptor.forClass(TransportOrder.class);
        verify(orderRepository).save(orderCaptor.capture());
        TransportOrder capturedOrder = orderCaptor.getValue();
        assertEquals(company, capturedOrder.getCompany());
        assertEquals(OrderStatus.PENDING, capturedOrder.getStatus());
        assertEquals(new BigDecimal("20.00"), capturedOrder.getTotalWeightKg());
        assertNull(capturedOrder.getCustomer());

        // Verify CargoPackages linked to order
        ArgumentCaptor<CargoPackage> pkgCaptor = ArgumentCaptor.forClass(CargoPackage.class);
        verify(cargoPackageRepository, times(2)).save(pkgCaptor.capture());
        List<CargoPackage> capturedPkgs = pkgCaptor.getAllValues();
        assertEquals(2, capturedPkgs.size());
        for (CargoPackage capturedPkg : capturedPkgs) {
            assertEquals(savedOrder, capturedPkg.getOrder());
            assertEquals(company, capturedPkg.getCompany());
            assertEquals(PackageStatus.PENDING, capturedPkg.getStatus());
        }
    }

    @Test
    @DisplayName("confirm import throws exception and does NOT create order if preview has errors")
    void confirmImport_whenHasErrors_doesNotCreateOrder() {
        // Given
        Long companyId = 1L;
        Long userId = 99L;

        PackageImportRow errorRow = new PackageImportRow(
                1, "INVALID",
                null, null, null, null, null, null
        );
        ImportPreviewResult errorPreview = new ImportPreviewResult(
                1, 0,
                List.of(new fu.se184491.loadmaster_be.dto.response.RowError(1, "length", ErrorCode.IMPORT_INVALID_LENGTH)),
                Collections.emptyList()
        );

        when(csvExcelParserService.parse(mockFile)).thenReturn(List.of(errorRow));
        when(packageImportValidatorService.validate(any(), eq(companyId))).thenReturn(errorPreview);

        // When & Then
        ImportValidationException exception = assertThrows(ImportValidationException.class, () ->
                packageImportService.confirm(mockFile, companyId, userId, null)
        );
        assertEquals(ErrorCode.IMPORT_HAS_ERRORS, exception.getErrorCode());
        assertNotNull(exception.getPreviewResult());
        assertEquals(1, exception.getPreviewResult().errors().size());
        assertEquals("length", exception.getPreviewResult().errors().get(0).field());

        verify(orderRepository, never()).save(any());
        verify(cargoPackageRepository, never()).save(any());
    }
}
