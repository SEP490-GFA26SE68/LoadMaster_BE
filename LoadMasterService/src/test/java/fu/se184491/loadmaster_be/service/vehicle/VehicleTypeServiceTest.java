package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.dto.request.vehicle.VehicleTypeRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleTypeResponse;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleTypeRepository;
import fu.se184491.loadmaster_be.service.vehicle.Impl.VehicleTypeServiceImpl;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class VehicleTypeServiceTest {

    @Mock
    private VehicleTypeRepository vehicleTypeRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private VehicleTypeServiceImpl vehicleTypeService;

    private Company testCompany;
    private VehicleType testVehicleType;
    private VehicleTypeRequest testRequest;

    @BeforeEach
    void setUp() {
        testCompany = Company.builder().id(1L).build();
        testVehicleType = VehicleType.builder()
                .id(1L)
                .company(testCompany)
                .name("Xe tải 5 tấn")
                .innerLength(4200)
                .innerWidth(2000)
                .innerHeight(1800)
                .maxPayloadKg(BigDecimal.valueOf(5000.00))
                .build();
                
        testRequest = VehicleTypeRequest.builder()
                .name("Xe tải 5 tấn mới")
                .innerLength(4300)
                .innerWidth(2100)
                .innerHeight(1900)
                .maxPayloadKg(BigDecimal.valueOf(5500.00))
                .build();
    }

    @Test
    @DisplayName("Create VehicleType successfully")
    void createVehicleType_Success() {
        when(entityManager.getReference(Company.class, 1L)).thenReturn(testCompany);
        when(vehicleTypeRepository.save(any(VehicleType.class))).thenReturn(testVehicleType);

        VehicleTypeResponse response = vehicleTypeService.createVehicleType(1L, testRequest);

        assertNotNull(response);
        assertEquals(testVehicleType.getId(), response.getId());
        assertEquals("Xe tải 5 tấn", response.getName());
        assertEquals(4200, response.getInnerLength());
        // Verify save was called
        verify(vehicleTypeRepository, times(1)).save(any(VehicleType.class));
    }

    @Test
    @DisplayName("Update VehicleType successfully")
    void updateVehicleType_Success() {
        when(vehicleTypeRepository.findById(1L)).thenReturn(Optional.of(testVehicleType));
        when(vehicleTypeRepository.save(any(VehicleType.class))).thenReturn(testVehicleType);

        VehicleTypeResponse response = vehicleTypeService.updateVehicleType(1L, 1L, testRequest);

        assertNotNull(response);
        assertEquals("Xe tải 5 tấn mới", testVehicleType.getName()); // entity was mutated
        verify(vehicleTypeRepository, times(1)).save(testVehicleType);
    }

    @Test
    @DisplayName("Update VehicleType fails - Unauthorized company")
    void updateVehicleType_Unauthorized() {
        when(vehicleTypeRepository.findById(1L)).thenReturn(Optional.of(testVehicleType));

        AppException exception = assertThrows(AppException.class, 
                () -> vehicleTypeService.updateVehicleType(2L, 1L, testRequest));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
        verify(vehicleTypeRepository, never()).save(any());
    }

    @Test
    @DisplayName("Get VehicleType By Id successfully")
    void getVehicleTypeById_Success() {
        when(vehicleTypeRepository.findById(1L)).thenReturn(Optional.of(testVehicleType));

        VehicleTypeResponse response = vehicleTypeService.getVehicleTypeById(1L, 1L);

        assertNotNull(response);
        assertEquals(testVehicleType.getId(), response.getId());
    }

    @Test
    @DisplayName("Get VehicleType By Id fails - Unauthorized company")
    void getVehicleTypeById_Unauthorized() {
        when(vehicleTypeRepository.findById(1L)).thenReturn(Optional.of(testVehicleType));

        AppException exception = assertThrows(AppException.class, 
                () -> vehicleTypeService.getVehicleTypeById(2L, 1L));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    @Test
    @DisplayName("Get all VehicleTypes with pagination")
    void getAllVehicleTypes_Success() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<VehicleType> page = new PageImpl<>(List.of(testVehicleType));
        when(vehicleTypeRepository.findByCompanyId(1L, pageable)).thenReturn(page);

        Page<VehicleTypeResponse> responsePage = vehicleTypeService.getAllVehicleTypes(1L, pageable);

        assertNotNull(responsePage);
        assertEquals(1, responsePage.getTotalElements());
        assertEquals("Xe tải 5 tấn", responsePage.getContent().get(0).getName());
    }

    @Test
    @DisplayName("Delete VehicleType successfully")
    void deleteVehicleType_Success() {
        when(vehicleTypeRepository.findById(1L)).thenReturn(Optional.of(testVehicleType));
        doNothing().when(vehicleTypeRepository).delete(testVehicleType);

        assertDoesNotThrow(() -> vehicleTypeService.deleteVehicleType(1L, 1L));

        verify(vehicleTypeRepository, times(1)).delete(testVehicleType);
    }
}
