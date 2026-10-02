package fu.se184491.loadmaster_be.controller.cargo;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.dto.request.packagetype.PackageTypeRequest;
import fu.se184491.loadmaster_be.dto.response.packagetype.PackageTypeResponse;
import fu.se184491.loadmaster_be.service.cargo.PackageTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PackageTypeController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
public class PackageTypeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PackageTypeService packageTypeService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private ObjectMapper objectMapper;
    private PackageTypeRequest request;
    private PackageTypeResponse response;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        request = PackageTypeRequest.builder()
                .name("Thùng nhỏ")
                .length(400)
                .width(300)
                .height(300)
                .weightKg(BigDecimal.valueOf(12.50))
                .maxStackingWeightKg(BigDecimal.valueOf(50.00))
                .isFragile(false)
                .build();

        response = PackageTypeResponse.builder()
                .id(100L)
                .companyId(1L)
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
    @DisplayName("POST /api/package-types should return 201 Created when request is valid")
    @WithMockUser(authorities = "PACKAGE_TYPE_MANAGE")
    void createPackageType_Success() throws Exception {
        when(packageTypeService.createPackageType(eq(1L), any(PackageTypeRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/package-types")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.name").value("Thùng nhỏ"))
                .andExpect(jsonPath("$.length").value(400))
                .andExpect(jsonPath("$.width").value(300))
                .andExpect(jsonPath("$.height").value(300))
                .andExpect(jsonPath("$.weightKg").value(12.50))
                .andExpect(jsonPath("$.maxStackingWeightKg").value(50.00))
                .andExpect(jsonPath("$.isFragile").value(false));
    }

    @Test
    @DisplayName("POST /api/package-types should return 400 Bad Request when validation fails")
    @WithMockUser(authorities = "PACKAGE_TYPE_MANAGE")
    void createPackageType_ValidationError() throws Exception {
        PackageTypeRequest invalidRequest = PackageTypeRequest.builder()
                .name("") // blank name
                .length(-1) // negative length
                .width(0) // non-positive width
                .height(null) // null height
                .weightKg(BigDecimal.valueOf(-5)) // negative weight
                .maxStackingWeightKg(BigDecimal.valueOf(-1)) // negative max stack
                .build();

        mockMvc.perform(post("/api/package-types")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());

        verify(packageTypeService, never()).createPackageType(any(), any());
    }

    @Test
    @DisplayName("PUT /api/package-types/{id} should return 200 OK when request is valid")
    @WithMockUser(authorities = "PACKAGE_TYPE_MANAGE")
    void updatePackageType_Success() throws Exception {
        when(packageTypeService.updatePackageType(eq(1L), eq(100L), any(PackageTypeRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/package-types/100")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.name").value("Thùng nhỏ"));
    }

    @Test
    @DisplayName("GET /api/package-types/{id} should return 200 OK")
    @WithMockUser(authorities = "PACKAGE_TYPE_MANAGE")
    void getPackageTypeById_Success() throws Exception {
        when(packageTypeService.getPackageTypeById(1L, 100L)).thenReturn(response);

        mockMvc.perform(get("/api/package-types/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.name").value("Thùng nhỏ"));
    }

    @Test
    @DisplayName("GET /api/package-types should return 200 OK with Page")
    @WithMockUser(authorities = "PACKAGE_TYPE_MANAGE")
    void getAllPackageTypes_Success() throws Exception {
        Page<PackageTypeResponse> page = new PageImpl<>(Collections.singletonList(response));
        when(packageTypeService.getAllPackageTypes(eq(1L), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/package-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(100L))
                .andExpect(jsonPath("$.content[0].name").value("Thùng nhỏ"));
    }

    @Test
    @DisplayName("DELETE /api/package-types/{id} should return 204 No Content")
    @WithMockUser(authorities = "PACKAGE_TYPE_MANAGE")
    void deletePackageType_Success() throws Exception {
        doNothing().when(packageTypeService).deletePackageType(1L, 100L);

        mockMvc.perform(delete("/api/package-types/100")
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(packageTypeService).deletePackageType(1L, 100L);
    }
}
