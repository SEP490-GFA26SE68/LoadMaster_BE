package fu.se184491.loadmaster_be.controller.vehicle;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.dto.request.vehicle.VehicleRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import fu.se184491.loadmaster_be.service.vehicle.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

@WebMvcTest(VehicleController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false) // By-pass security filters for unit test
public class VehicleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VehicleService vehicleService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private ObjectMapper objectMapper;
    private VehicleRequest request;
    private VehicleResponse response;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        request = VehicleRequest.builder()
                .vehicleTypeId(1L)
                .licensePlate("51C-123.45")
                .frontAxleLimitKg(new BigDecimal("2500.00"))
                .rearAxleLimitKg(new BigDecimal("5000.00"))
                .driverUserId(2L)
                .build();

        response = VehicleResponse.builder()
                .id(1L)
                .vehicleTypeId(1L)
                .licensePlate("51C-123.45")
                .frontAxleLimitKg(new BigDecimal("2500.00"))
                .rearAxleLimitKg(new BigDecimal("5000.00"))
                .companyId(1L)
                .driverUserId(2L)
                .build();
    }

    @Test
    @WithMockUser(authorities = "VEHICLE_MANAGE")
    void createVehicle_Success() throws Exception {
        when(vehicleService.createVehicle(eq(1L), any(VehicleRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/vehicles")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.licensePlate").value("51C-123.45"));
    }

    @Test
    @WithMockUser(authorities = "VEHICLE_MANAGE")
    void getAllVehicles_Success() throws Exception {
        Page<VehicleResponse> page = new PageImpl<>(Collections.singletonList(response));
        
        when(vehicleService.getAllVehicles(eq(1L), any(), any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1L));
    }
}
