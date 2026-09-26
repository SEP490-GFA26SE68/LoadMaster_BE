package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.dto.request.vehicle.VehicleRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleTypeRepository;
import fu.se184491.loadmaster_be.service.vehicle.Impl.VehicleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class VehicleServiceTest {

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private VehicleTypeRepository vehicleTypeRepository;
    
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private VehicleServiceImpl vehicleService;

    private VehicleRequest validRequest;
    private VehicleType vehicleType;
    private User driver;
    private Company company;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        validRequest = VehicleRequest.builder()
                .vehicleTypeId(1L)
                .licensePlate("51C-123.45")
                .frontAxleLimitKg(new BigDecimal("2500.00"))
                .rearAxleLimitKg(new BigDecimal("5000.00"))
                .driverUserId(2L)
                .build();

        company = Company.builder().id(1L).build();
        
        vehicleType = VehicleType.builder()
                .id(1L)
                .company(company)
                .build();
                
        driver = User.builder()
                .id(2L)
                .company(company)
                .build();

        vehicle = Vehicle.builder()
                .id(1L)
                .vehicleType(vehicleType)
                .licensePlate("51C-123.45")
                .frontAxleLimitKg(new BigDecimal("2500.00"))
                .rearAxleLimitKg(new BigDecimal("5000.00"))
                .driver(driver)
                .company(company)
                .build();
    }

    @Test
    void createVehicle_Success() {
        when(vehicleTypeRepository.findById(1L)).thenReturn(Optional.of(vehicleType));
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(vehicleRepository.existsByLicensePlate("51C-123.45")).thenReturn(false);
        when(vehicleRepository.save(any(Vehicle.class))).thenReturn(vehicle);

        VehicleResponse response = vehicleService.createVehicle(1L, validRequest);

        assertNotNull(response);
        assertEquals("51C-123.45", response.getLicensePlate());
        assertEquals(1L, response.getCompanyId());
        assertEquals(1L, response.getVehicleTypeId());
        assertEquals(2L, response.getDriverUserId());
    }

    @Test
    void createVehicle_VehicleTypeNotFound() {
        when(vehicleTypeRepository.findById(1L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> vehicleService.createVehicle(1L, validRequest));
        assertEquals(ErrorCode.VEHICLE_TYPE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void updateVehicle_Success() {
        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(vehicle));
        when(vehicleTypeRepository.findById(1L)).thenReturn(Optional.of(vehicleType));
        when(userRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(vehicleRepository.save(any(Vehicle.class))).thenReturn(vehicle);

        VehicleResponse response = vehicleService.updateVehicle(1L, 1L, validRequest);

        assertNotNull(response);
        assertEquals("51C-123.45", response.getLicensePlate());
    }

    @Test
    void updateVehicle_VehicleNotFound() {
        when(vehicleRepository.findById(1L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> vehicleService.updateVehicle(1L, 1L, validRequest));
        assertEquals(ErrorCode.VEHICLE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getVehicleById_Success() {
        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(vehicle));

        VehicleResponse response = vehicleService.getVehicleById(1L, 1L);

        assertNotNull(response);
        assertEquals(1L, response.getId());
    }

    @Test
    void deleteVehicle_Success() {
        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(vehicle));

        vehicleService.deleteVehicle(1L, 1L);

        verify(vehicleRepository, times(1)).delete(vehicle);
    }
}
