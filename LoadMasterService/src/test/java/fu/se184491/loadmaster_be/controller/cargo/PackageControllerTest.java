package fu.se184491.loadmaster_be.controller.cargo;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.dto.request.cargo.PackageRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.PackageResponse;
import fu.se184491.loadmaster_be.service.cargo.PackageService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PackageController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
public class PackageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PackageService packageService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private fu.se184491.loadmaster_be.config.security.RolePermissionMapping rolePermissionMapping;

    @MockitoBean
    private fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler accessDeniedHandler;

    private ObjectMapper objectMapper;
    private PackageRequest request;
    private PackageResponse response;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        request = PackageRequest.builder()
                .orderId(10L)
                .packageTypeId(20L)
                .actualLength(300)
                .actualWeightKg(new BigDecimal("15.50"))
                .isPinned(false)
                .build();

        response = PackageResponse.builder()
                .id(100L)
                .orderId(10L)
                .packageTypeId(20L)
                .trackingBarcode("PKG-123456")
                .actualLength(300)
                .actualWeightKg(new BigDecimal("15.50"))
                .isPinned(false)
                .build();
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_MANAGE")
    void createPackage_Success() throws Exception {
        when(packageService.createPackage(eq(1L), any(PackageRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/packages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.orderId").value(10L))
                .andExpect(jsonPath("$.trackingBarcode").value("PKG-123456"))
                .andExpect(jsonPath("$.actualLength").value(300))
                .andExpect(jsonPath("$.actualWeightKg").value(15.50));
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_MANAGE")
    void createPackage_WithoutOrderId_Success() throws Exception {
        PackageRequest standaloneRequest = PackageRequest.builder()
                .actualLength(300)
                .actualWeightKg(new BigDecimal("15.50"))
                .build();

        PackageResponse standaloneResponse = PackageResponse.builder()
                .id(101L)
                .orderId(null)
                .trackingBarcode("PKG-NO-ORDER")
                .actualLength(300)
                .actualWeightKg(new BigDecimal("15.50"))
                .build();

        when(packageService.createPackage(eq(1L), any(PackageRequest.class))).thenReturn(standaloneResponse);

        mockMvc.perform(post("/api/packages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(standaloneRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(101L))
                .andExpect(jsonPath("$.orderId").doesNotExist());
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_MANAGE")
    void createPackage_ValidationError_InvalidActualLength() throws Exception {
        request.setActualLength(0); // must be > 0

        mockMvc.perform(post("/api/packages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_MANAGE")
    void updatePackage_Success() throws Exception {
        when(packageService.updatePackage(eq(1L), eq(100L), any(PackageRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/packages/100")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.trackingBarcode").value("PKG-123456"));
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_MANAGE")
    void getPackageById_Success() throws Exception {
        when(packageService.getPackageById(eq(1L), eq(100L))).thenReturn(response);

        mockMvc.perform(get("/api/packages/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.trackingBarcode").value("PKG-123456"));
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_MANAGE")
    void deletePackage_Success() throws Exception {
        doNothing().when(packageService).deletePackage(1L, 100L);

        mockMvc.perform(delete("/api/packages/100")
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

}
