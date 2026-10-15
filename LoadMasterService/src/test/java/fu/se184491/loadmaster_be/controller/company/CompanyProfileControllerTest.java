package fu.se184491.loadmaster_be.controller.company;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler;
import fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.dto.request.company.UpdateCompanyProfileRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.company.CompanyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CompanyProfileController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class CompanyProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CompanyService companyService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private RolePermissionMapping rolePermissionMapping;

    @MockitoBean
    private RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private RestAccessDeniedHandler accessDeniedHandler;

    private ObjectMapper objectMapper;
    private CompanyResponse sampleResponse;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        sampleResponse = CompanyResponse.builder()
                .id(10L)
                .companyCode("COMP-10")
                .companyName("Công ty Vận Tải An Phát")
                .taxCode("0101234567")
                .billingEmail("billing@anphat.vn")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();

        when(currentUserService.getCurrentCompanyId()).thenReturn(10L);
    }

    @Test
    @DisplayName("GET /api/company/profile should return current company profile")
    @WithMockUser(roles = "ADMIN")
    void getCompanyProfile_success() throws Exception {
        when(companyService.getCompanyProfile(10L)).thenReturn(sampleResponse);

        mockMvc.perform(get("/api/company/profile")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.companyCode").value("COMP-10"))
                .andExpect(jsonPath("$.companyName").value("Công ty Vận Tải An Phát"))
                .andExpect(jsonPath("$.taxCode").value("0101234567"))
                .andExpect(jsonPath("$.billingEmail").value("billing@anphat.vn"));
    }

    @Test
    @DisplayName("PATCH /api/company/profile should update profile and return updated response")
    @WithMockUser(roles = "ADMIN")
    void updateCompanyProfile_success() throws Exception {
        UpdateCompanyProfileRequest updateReq = UpdateCompanyProfileRequest.builder()
                .companyName("Công ty An Phát Mới")
                .contactEmail("contact@anphat.vn")
                .address("Số 1 Đại Cồ Việt, Hà Nội")
                .contactPhone("0987654321")
                .build();

        CompanyResponse updatedResponse = CompanyResponse.builder()
                .id(10L)
                .companyCode("COMP-10")
                .companyName("Công ty An Phát Mới")
                .taxCode("0101234567")
                .billingEmail("contact@anphat.vn")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();

        when(companyService.updateCompanyProfile(eq(10L), any(UpdateCompanyProfileRequest.class)))
                .thenReturn(updatedResponse);

        mockMvc.perform(patch("/api/company/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.companyName").value("Công ty An Phát Mới"))
                .andExpect(jsonPath("$.billingEmail").value("contact@anphat.vn"));
    }

    @Test
    @DisplayName("PATCH /api/company/profile with invalid email should return 400 Bad Request")
    @WithMockUser(roles = "ADMIN")
    void updateCompanyProfile_invalidEmail() throws Exception {
        UpdateCompanyProfileRequest invalidReq = UpdateCompanyProfileRequest.builder()
                .companyName("Công ty An Phát Mới")
                .contactEmail("invalid-email-format")
                .build();

        mockMvc.perform(patch("/api/company/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /api/company/profile with blank companyName should return 400 Bad Request")
    @WithMockUser(roles = "ADMIN")
    void updateCompanyProfile_blankCompanyName() throws Exception {
        UpdateCompanyProfileRequest invalidReq = UpdateCompanyProfileRequest.builder()
                .companyName("")
                .contactEmail("contact@anphat.vn")
                .build();

        mockMvc.perform(patch("/api/company/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isBadRequest());
    }
}
