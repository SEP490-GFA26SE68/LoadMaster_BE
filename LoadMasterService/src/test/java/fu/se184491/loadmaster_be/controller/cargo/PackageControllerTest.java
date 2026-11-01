package fu.se184491.loadmaster_be.controller.cargo;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.dto.request.cargo.PackageRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.PackageResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.cargo.PackageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
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
    private CurrentUserService currentUserService;

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
        lenient().when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        request = PackageRequest.builder()
                .orderId(10L)
                .packageTypeId(20L)
                .actualLength(new BigDecimal("2.22"))
                .actualWidth(new BigDecimal("3.45"))
                .actualHeight(new BigDecimal("4.56"))
                .actualWeightKg(new BigDecimal("15.50"))
                .handlingClass(HandlingClass.STANDARD)
                .destination("HCM")
                .isPinned(false)
                .build();

        response = PackageResponse.builder()
                .id(100L)
                .orderId(10L)
                .packageTypeId(20L)
                .trackingBarcode("PKG-123456")
                .actualLength(new BigDecimal("2.22"))
                .actualWidth(new BigDecimal("3.45"))
                .actualHeight(new BigDecimal("4.56"))
                .actualWeightKg(new BigDecimal("15.50"))
                .handlingClass(HandlingClass.STANDARD)
                .destination("HCM")
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
                .andExpect(jsonPath("$.data.id").value(100L))
                .andExpect(jsonPath("$.data.orderId").value(10L))
                .andExpect(jsonPath("$.data.trackingBarcode").value("PKG-123456"))
                .andExpect(jsonPath("$.data.actualLength").value(2.22))
                .andExpect(jsonPath("$.data.actualWeightKg").value(15.50));
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_MANAGE")
    void createPackage_WithoutOrderId_Success() throws Exception {
        PackageRequest standaloneRequest = PackageRequest.builder()
                .actualLength(new BigDecimal("30.25"))
                .actualWidth(new BigDecimal("20.50"))
                .actualHeight(new BigDecimal("10.75"))
                .actualWeightKg(new BigDecimal("15.50"))
                .handlingClass(HandlingClass.STANDARD)
                .destination("Ha Noi")
                .build();

        PackageResponse standaloneResponse = PackageResponse.builder()
                .id(101L)
                .orderId(null)
                .trackingBarcode("PKG-NO-ORDER")
                .actualLength(new BigDecimal("30.25"))
                .actualWidth(new BigDecimal("20.50"))
                .actualHeight(new BigDecimal("10.75"))
                .actualWeightKg(new BigDecimal("15.50"))
                .build();

        when(packageService.createPackage(eq(1L), any(PackageRequest.class))).thenReturn(standaloneResponse);

        mockMvc.perform(post("/api/packages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(standaloneRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(101L))
                .andExpect(jsonPath("$.data.orderId").isEmpty());
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_MANAGE")
    void createPackage_ValidationError_InvalidActualLength() throws Exception {
        request.setActualLength(BigDecimal.ZERO); // must be > 0

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
                .andExpect(jsonPath("$.data.id").value(100L))
                .andExpect(jsonPath("$.data.trackingBarcode").value("PKG-123456"));
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_MANAGE")
    void getPackageById_Success() throws Exception {
        when(packageService.getPackageById(eq(1L), eq(100L))).thenReturn(response);

        mockMvc.perform(get("/api/packages/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(100L))
                .andExpect(jsonPath("$.data.trackingBarcode").value("PKG-123456"));
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_MANAGE")
    void deletePackage_Success() throws Exception {
        doNothing().when(packageService).deletePackage(1L, 100L);

        mockMvc.perform(delete("/api/packages/100")
                        .with(csrf()))
                .andExpect(status().isOk());
    }

}
