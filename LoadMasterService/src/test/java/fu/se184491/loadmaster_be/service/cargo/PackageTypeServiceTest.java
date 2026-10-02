package fu.se184491.loadmaster_be.service.cargo;

import fu.se184491.loadmaster_be.dto.request.packagetype.PackageTypeRequest;
import fu.se184491.loadmaster_be.dto.response.packagetype.PackageTypeResponse;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.PackageTypeRepository;
import fu.se184491.loadmaster_be.service.cargo.Impl.PackageTypeServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PackageTypeServiceTest {

    @Mock
    private PackageTypeRepository packageTypeRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private PackageTypeServiceImpl packageTypeService;

    private PackageTypeRequest request;
    private PackageType packageType;
    private Company company;

    @BeforeEach
    void setUp() {
        company = Company.builder().id(1L).build();

        request = PackageTypeRequest.builder()
                .name("Thùng nhỏ")
                .length(400)
                .width(300)
                .height(300)
                .weightKg(BigDecimal.valueOf(12.50))
                .maxStackingWeightKg(BigDecimal.valueOf(50.00))
                .isFragile(false)
                .build();

        packageType = PackageType.builder()
                .id(100L)
                .company(company)
                .name("Thùng nhỏ")
                .length(400)
                .width(300)
                .height(300)
                .weightKg(BigDecimal.valueOf(12.50))
                .maxStackingWeightKg(BigDecimal.valueOf(50.00))
                .isFragile(false)
                .build();
    }

    @Test
    @DisplayName("Create PackageType should succeed with valid data")
    void createPackageType_success() {
        when(entityManager.getReference(Company.class, 1L)).thenReturn(company);
        when(packageTypeRepository.save(any(PackageType.class))).thenReturn(packageType);

        PackageTypeResponse response = packageTypeService.createPackageType(1L, request);

        assertNotNull(response);
        assertEquals(100L, response.getId());
        assertEquals(1L, response.getCompanyId());
        assertEquals("Thùng nhỏ", response.getName());
        assertEquals(400, response.getLength());
        assertEquals(300, response.getWidth());
        assertEquals(300, response.getHeight());
        assertEquals(BigDecimal.valueOf(12.50), response.getWeightKg());
        assertEquals(BigDecimal.valueOf(50.00), response.getMaxStackingWeightKg());
        assertFalse(response.getIsFragile());

        verify(packageTypeRepository).save(any(PackageType.class));
    }

    @Test
    @DisplayName("Update PackageType should succeed when entity exists")
    void updatePackageType_success() {
        when(packageTypeRepository.findByIdAndCompanyId(100L, 1L)).thenReturn(Optional.of(packageType));
        when(packageTypeRepository.save(any(PackageType.class))).thenReturn(packageType);

        PackageTypeRequest updateRequest = PackageTypeRequest.builder()
                .name("Thùng trung")
                .length(500)
                .width(400)
                .height(350)
                .weightKg(BigDecimal.valueOf(20.00))
                .maxStackingWeightKg(BigDecimal.valueOf(80.00))
                .isFragile(true)
                .build();

        PackageTypeResponse response = packageTypeService.updatePackageType(1L, 100L, updateRequest);

        assertNotNull(response);
        assertEquals("Thùng trung", packageType.getName());
        assertEquals(500, packageType.getLength());
        assertEquals(400, packageType.getWidth());
        assertEquals(350, packageType.getHeight());
        assertEquals(BigDecimal.valueOf(20.00), packageType.getWeightKg());
        assertEquals(BigDecimal.valueOf(80.00), packageType.getMaxStackingWeightKg());
        assertTrue(packageType.getIsFragile());

        verify(packageTypeRepository).save(packageType);
    }

    @Test
    @DisplayName("Update PackageType should throw PACKAGE_TYPE_NOT_FOUND when entity does not exist")
    void updatePackageType_notFound() {
        when(packageTypeRepository.findByIdAndCompanyId(999L, 1L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () ->
                packageTypeService.updatePackageType(1L, 999L, request)
        );

        assertEquals(ErrorCode.PACKAGE_TYPE_NOT_FOUND, ex.getErrorCode());
        verify(packageTypeRepository, never()).save(any());
    }

    @Test
    @DisplayName("Get PackageType by ID should succeed when entity exists")
    void getPackageTypeById_success() {
        when(packageTypeRepository.findByIdAndCompanyId(100L, 1L)).thenReturn(Optional.of(packageType));

        PackageTypeResponse response = packageTypeService.getPackageTypeById(1L, 100L);

        assertNotNull(response);
        assertEquals(100L, response.getId());
        assertEquals("Thùng nhỏ", response.getName());
    }

    @Test
    @DisplayName("Get PackageType by ID should throw PACKAGE_TYPE_NOT_FOUND when entity does not exist")
    void getPackageTypeById_notFound() {
        when(packageTypeRepository.findByIdAndCompanyId(999L, 1L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () ->
                packageTypeService.getPackageTypeById(1L, 999L)
        );

        assertEquals(ErrorCode.PACKAGE_TYPE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("Get all PackageTypes with pagination should return Page of response")
    void getAllPackageTypes_pageable() {
        Pageable pageable = mock(Pageable.class);
        Page<PackageType> page = new PageImpl<>(List.of(packageType));
        when(packageTypeRepository.findByCompanyId(1L, pageable)).thenReturn(page);

        Page<PackageTypeResponse> result = packageTypeService.getAllPackageTypes(1L, pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals("Thùng nhỏ", result.getContent().get(0).getName());
    }

    @Test
    @DisplayName("Get all PackageTypes list should return List of response")
    void getAllPackageTypes_list() {
        when(packageTypeRepository.findByCompanyId(1L)).thenReturn(List.of(packageType));

        List<PackageTypeResponse> result = packageTypeService.getAllPackageTypes(1L);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Thùng nhỏ", result.get(0).getName());
    }

    @Test
    @DisplayName("Delete PackageType should succeed when entity exists")
    void deletePackageType_success() {
        when(packageTypeRepository.findByIdAndCompanyId(100L, 1L)).thenReturn(Optional.of(packageType));
        doNothing().when(packageTypeRepository).delete(packageType);

        assertDoesNotThrow(() -> packageTypeService.deletePackageType(1L, 100L));

        verify(packageTypeRepository).delete(packageType);
    }

    @Test
    @DisplayName("Delete PackageType should throw PACKAGE_TYPE_NOT_FOUND when entity does not exist")
    void deletePackageType_notFound() {
        when(packageTypeRepository.findByIdAndCompanyId(999L, 1L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () ->
                packageTypeService.deletePackageType(1L, 999L)
        );

        assertEquals(ErrorCode.PACKAGE_TYPE_NOT_FOUND, ex.getErrorCode());
        verify(packageTypeRepository, never()).delete(any());
    }
}
