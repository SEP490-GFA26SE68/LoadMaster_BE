package fu.se184491.loadmaster_be.controller.vehicle;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.dto.request.vehicle.VehicleTypeRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleTypeResponse;
import fu.se184491.loadmaster_be.service.vehicle.VehicleTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

@WebMvcTest(controllers = VehicleTypeController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false) // Bypass security filters for unit testing controller logic
public class VehicleTypeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VehicleTypeService vehicleTypeService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private ObjectMapper objectMapper;

    private VehicleTypeRequest validRequest;
    private VehicleTypeResponse responseObj;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        
        validRequest = VehicleTypeRequest.builder()
                .name("Xe tải 5 tấn")
                .innerLength(4200)
                .innerWidth(2000)
                .innerHeight(1800)
                .maxPayloadKg(BigDecimal.valueOf(5000.00))
                .build();

        responseObj = VehicleTypeResponse.builder()
                .id(1L)
                .name("Xe tải 5 tấn")
                .innerLength(4200)
                .innerWidth(2000)
                .innerHeight(1800)
                .maxPayloadKg(BigDecimal.valueOf(5000.00))
                .companyId(1L)
                .build();
    }

    @Test
    @DisplayName("POST /api/vehicle-types - Success")
    @WithMockUser(authorities = "VEHICLE_TYPE_MANAGE")
    void createVehicleType_Success() throws Exception {
        Mockito.when(vehicleTypeService.createVehicleType(eq(1L), any(VehicleTypeRequest.class)))
               .thenReturn(responseObj);

        mockMvc.perform(post("/api/vehicle-types")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("Xe tải 5 tấn"));
    }

    @Test
    @DisplayName("POST /api/vehicle-types - Fail Validation")
    @WithMockUser(authorities = "VEHICLE_TYPE_MANAGE")
    void createVehicleType_ValidationFail() throws Exception {
        // Missing name and negative dimensions
        VehicleTypeRequest invalidRequest = VehicleTypeRequest.builder()
                .innerLength(-10)
                .build();

        mockMvc.perform(post("/api/vehicle-types")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/vehicle-types/{id} - Success")
    @WithMockUser(authorities = "VEHICLE_TYPE_MANAGE")
    void getVehicleTypeById_Success() throws Exception {
        Mockito.when(vehicleTypeService.getVehicleTypeById(1L, 1L)).thenReturn(responseObj);

        mockMvc.perform(get("/api/vehicle-types/1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Xe tải 5 tấn"));
    }

    @Test
    @DisplayName("GET /api/vehicle-types - Paging Success")
    @WithMockUser(authorities = "VEHICLE_TYPE_MANAGE")
    void getAllVehicleTypes_Success() throws Exception {
        Page<VehicleTypeResponse> page = new PageImpl<>(List.of(responseObj));
        Mockito.when(vehicleTypeService.getAllVehicleTypes(eq(1L), any(Pageable.class)))
               .thenReturn(page);

        mockMvc.perform(get("/api/vehicle-types?page=0&size=10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Xe tải 5 tấn"));
    }

    @Test
    @DisplayName("PUT /api/vehicle-types/{id} - Success")
    @WithMockUser(authorities = "VEHICLE_TYPE_MANAGE")
    void updateVehicleType_Success() throws Exception {
        Mockito.when(vehicleTypeService.updateVehicleType(eq(1L), eq(1L), any(VehicleTypeRequest.class)))
               .thenReturn(responseObj);

        mockMvc.perform(put("/api/vehicle-types/1")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Xe tải 5 tấn"));
    }

    @Test
    @DisplayName("DELETE /api/vehicle-types/{id} - Success")
    @WithMockUser(authorities = "VEHICLE_TYPE_MANAGE")
    void deleteVehicleType_Success() throws Exception {
        Mockito.doNothing().when(vehicleTypeService).deleteVehicleType(1L, 1L);

        mockMvc.perform(delete("/api/vehicle-types/1")
                .with(csrf()))
                .andExpect(status().isNoContent());
    }
}
