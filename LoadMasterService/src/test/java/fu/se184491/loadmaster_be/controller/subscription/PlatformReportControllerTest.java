package fu.se184491.loadmaster_be.controller.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler;
import fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.subscription.PlatformReportSummaryResponse;
import fu.se184491.loadmaster_be.service.subscription.PlatformReportService;
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

import java.math.BigDecimal;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformReportController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class PlatformReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlatformReportService platformReportService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private RolePermissionMapping rolePermissionMapping;

    @MockitoBean
    private RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private RestAccessDeniedHandler accessDeniedHandler;

    private ObjectMapper objectMapper;
    private PlatformReportSummaryResponse sampleResponse;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        sampleResponse = PlatformReportSummaryResponse.builder()
                .totalCompanies(12)
                .companiesByTier(Map.of(
                        SubscriptionTier.BASIC, 5L,
                        SubscriptionTier.PRO, 4L,
                        SubscriptionTier.ULTIMATE, 3L
                ))
                .activeContracts(10)
                .expiringSoonContracts(2)
                .totalRevenue(BigDecimal.valueOf(150000000))
                .build();
    }

    @Test
    @DisplayName("GET /api/platform/reports/summary should return summary metrics")
    @WithMockUser(roles = "SYSTEM_MANAGER")
    void getSummaryReport_success() throws Exception {
        when(platformReportService.getSummaryReport()).thenReturn(sampleResponse);

        mockMvc.perform(get("/api/platform/reports/summary")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCompanies").value(12))
                .andExpect(jsonPath("$.activeContracts").value(10))
                .andExpect(jsonPath("$.expiringSoonContracts").value(2))
                .andExpect(jsonPath("$.totalRevenue").value(150000000))
                .andExpect(jsonPath("$.companiesByTier.BASIC").value(5))
                .andExpect(jsonPath("$.companiesByTier.PRO").value(4))
                .andExpect(jsonPath("$.companiesByTier.ULTIMATE").value(3));
    }
}
