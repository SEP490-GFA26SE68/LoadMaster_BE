package fu.se184491.loadmaster_be.service.cargo;

import fu.se184491.loadmaster_be.dto.request.cargo.PackageRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.PackageResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.PackageRepository;
import fu.se184491.loadmaster_be.repository.cargo.PackageTypeRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.order.OrderRepository;
import fu.se184491.loadmaster_be.service.cargo.Impl.PackageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PackageServiceTest {

    @Mock
    private PackageRepository packageRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PackageTypeRepository packageTypeRepository;

    @Mock
    private CompanyRepository companyRepository;

    @InjectMocks
    private PackageServiceImpl packageService;

    private Company company;
    private TransportOrder order;
    private PackageType packageType;
    private CargoPackage cargoPackage;
    private PackageRequest request;

    @BeforeEach
    void setUp() {
        company = Company.builder().id(1L).companyCode("CMP01").build();
        lenient().when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

        order = TransportOrder.builder()
                .id(10L)
                .company(company)
                .orderCode("ORD-CMP01-20261008-0001")
                .totalWeightKg(BigDecimal.ZERO)
                .build();

        packageType = PackageType.builder()
                .id(20L)
                .company(company)
                .name("Box A")
                .build();

        cargoPackage = CargoPackage.builder()
                .id(100L)
                .order(order)
                .packageType(packageType)
                .trackingBarcode("PKG-123456")
                .actualLength(new BigDecimal("300"))
                .actualWeightKg(new BigDecimal("15.50"))
                .isPinned(false)
                .build();

        request = PackageRequest.builder()
                .orderId(10L)
                .packageTypeId(20L)
                .actualLength(new BigDecimal("300"))
                .actualWeightKg(new BigDecimal("15.50"))
                .isPinned(false)
                .build();
    }

    @Test
    void createPackage_Success_RecalculatesOrderTotalWeight() {
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(packageTypeRepository.findById(20L)).thenReturn(Optional.of(packageType));
        lenient().when(packageRepository.existsByTrackingBarcode("PKG-123456")).thenReturn(false);

        when(packageRepository.save(any(CargoPackage.class))).thenAnswer(inv -> {
            CargoPackage pkg = inv.getArgument(0);
            pkg.setId(100L);
            return pkg;
        });

        // After save, when findByOrderId is called during weight recalculation:
        when(packageRepository.findByOrderId(10L)).thenReturn(List.of(cargoPackage));

        PackageResponse response = packageService.createPackage(1L, request);

        assertNotNull(response);
        assertEquals(100L, response.getId());
        assertEquals(10L, response.getOrderId());
        assertEquals(20L, response.getPackageTypeId());
        assertEquals(new BigDecimal("300"), response.getActualLength());
        assertEquals(new BigDecimal("15.50"), response.getActualWeightKg());
        assertFalse(response.getIsPinned());

        // Verify order.totalWeightKg was updated and saved
        ArgumentCaptor<TransportOrder> orderCaptor = ArgumentCaptor.forClass(TransportOrder.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertEquals(new BigDecimal("15.50"), orderCaptor.getValue().getTotalWeightKg());
    }

    @Test
    void createPackage_WithoutOrderId_Success_AssignsDirectCompany() {
        PackageRequest standaloneRequest = PackageRequest.builder()
                .orderId(null)
                .packageTypeId(20L)
                .actualLength(new BigDecimal("400"))
                .actualWeightKg(new BigDecimal("10.00"))
                .isPinned(false)
                .build();

        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(packageTypeRepository.findById(20L)).thenReturn(Optional.of(packageType));
        lenient().when(packageRepository.existsByTrackingBarcode("PKG-STANDALONE")).thenReturn(false);
        when(packageRepository.save(any(CargoPackage.class))).thenAnswer(inv -> {
            CargoPackage pkg = inv.getArgument(0);
            pkg.setId(101L);
            return pkg;
        });

        PackageResponse response = packageService.createPackage(1L, standaloneRequest);

        assertNotNull(response);
        assertEquals(101L, response.getId());
        assertNull(response.getOrderId());
        assertEquals(20L, response.getPackageTypeId());

        ArgumentCaptor<CargoPackage> pkgCaptor = ArgumentCaptor.forClass(CargoPackage.class);
        verify(packageRepository).save(pkgCaptor.capture());
        CargoPackage savedPkg = pkgCaptor.getValue();
        assertNull(savedPkg.getOrder());
        assertEquals(company, savedPkg.getCompany());

        verify(orderRepository, never()).save(any());
    }

    @Test
    void createPackage_Throws_OrderNotFound() {
        when(orderRepository.findById(10L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> packageService.createPackage(1L, request));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void createPackage_Throws_PackageTypeNotFound() {
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(packageTypeRepository.findById(20L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> packageService.createPackage(1L, request));
        assertEquals(ErrorCode.PACKAGE_TYPE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void updatePackage_Success_RecalculatesOrderTotalWeight() {
        when(packageRepository.findByIdAndCompany_Id(100L, 1L)).thenReturn(Optional.of(cargoPackage));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(packageTypeRepository.findById(20L)).thenReturn(Optional.of(packageType));
        when(packageRepository.save(any(CargoPackage.class))).thenAnswer(inv -> inv.getArgument(0));

        // Let's say weight updated to 25.00
        request.setActualWeightKg(new BigDecimal("25.00"));
        CargoPackage updatedPkg = CargoPackage.builder()
                .id(100L)
                .order(order)
                .actualWeightKg(new BigDecimal("25.00"))
                .build();
        when(packageRepository.findByOrderId(10L)).thenReturn(List.of(updatedPkg));

        PackageResponse response = packageService.updatePackage(1L, 100L, request);

        assertNotNull(response);
        assertEquals(new BigDecimal("25.00"), response.getActualWeightKg());
        verify(orderRepository).save(any(TransportOrder.class));
    }

    @Test
    void updatePackage_Throws_PackageNotFound() {
        when(packageRepository.findByIdAndCompany_Id(100L, 1L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> packageService.updatePackage(1L, 100L, request));
        assertEquals(ErrorCode.PACKAGE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void deletePackage_Success_RecalculatesOrderTotalWeight() {
        when(packageRepository.findByIdAndCompany_Id(100L, 1L)).thenReturn(Optional.of(cargoPackage));
        // When deleted, findByOrderId returns empty list (0 kg)
        when(packageRepository.findByOrderId(10L)).thenReturn(Collections.emptyList());

        packageService.deletePackage(1L, 100L);

        verify(packageRepository).delete(cargoPackage);
        ArgumentCaptor<TransportOrder> captor = ArgumentCaptor.forClass(TransportOrder.class);
        verify(orderRepository).save(captor.capture());
        assertEquals(BigDecimal.ZERO, captor.getValue().getTotalWeightKg());
    }

    @Test
    void deletePackage_Throws_NotFound() {
        when(packageRepository.findByIdAndCompany_Id(100L, 1L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> packageService.deletePackage(1L, 100L));
        assertEquals(ErrorCode.PACKAGE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getPackageById_Success() {
        when(packageRepository.findByIdAndCompany_Id(100L, 1L)).thenReturn(Optional.of(cargoPackage));

        PackageResponse response = packageService.getPackageById(1L, 100L);

        assertNotNull(response);
        assertEquals(100L, response.getId());
        assertEquals("PKG-123456", response.getTrackingBarcode());
    }
}
