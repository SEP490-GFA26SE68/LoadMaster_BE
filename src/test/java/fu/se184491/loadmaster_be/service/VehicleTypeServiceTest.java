package fu.se184491.loadmaster_be.service;

import fu.se184491.loadmaster_be.dto.request.VehicleTypeRequest;
import fu.se184491.loadmaster_be.entity.Company;
import fu.se184491.loadmaster_be.entity.VehicleType;
import fu.se184491.loadmaster_be.repository.VehicleTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class VehicleTypeServiceTest {

    @Mock
    private VehicleTypeRepository vehicleTypeRepository;

    // TODO: We will need a CompanyRepository to fetch company when creating, but for now we might mock it or pass it.
    // Let's assume we need CompanyService or Repository. For simplicity, let's say the Company entity is fetched.
    
    // I'll create the test class, but I notice I might need CompanyService to get the Company by ID for saving.
    // I will mock it if needed.
    
    // Let's stop here and think about the implementation details. I will write basic failing tests.

    @Test
    void shouldCreateVehicleType() {
        // Arrange
        VehicleTypeRequest request = VehicleTypeRequest.builder()
                .name("Xe tải 5 tấn")
                .innerLength(4200)
                .innerWidth(2000)
                .innerHeight(1800)
                .maxPayloadKg(BigDecimal.valueOf(5000.00))
                .doorWidth(1900)
                .doorHeight(1700)
                .build();
                
        // Assert
        // This is a stub for now. I will flesh it out in the next steps.
    }
}
