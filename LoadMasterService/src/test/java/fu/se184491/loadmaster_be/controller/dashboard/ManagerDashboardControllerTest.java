package fu.se184491.loadmaster_be.controller.dashboard;

import fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler;
import fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.dto.response.dashboard.ManagerDashboardResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.dashboard.ManagerDashboardCalculator;
import fu.se184491.loadmaster_be.service.dashboard.ManagerDashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ManagerDashboardController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class ManagerDashboardControllerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 5);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ManagerDashboardService managerDashboardService;
    @MockitoBean private CurrentUserService currentUserService;
    @MockitoBean private JwtAuthenticationConverter jwtAuthenticationConverter;
    @MockitoBean private RolePermissionMapping rolePermissionMapping;
    @MockitoBean private RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean private RestAccessDeniedHandler accessDeniedHandler;

    @Test
    @WithMockUser(roles = "MANAGER")
    void managerReadsDashboardOfOwnCompany() throws Exception {
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(managerDashboardService.get(1L, FROM, TO, 10))
                .thenReturn(ManagerDashboardCalculator.compute(FROM, TO, List.of(), List.of()));

        mockMvc.perform(get("/api/manager/dashboard").param("from", "2026-11-01").param("to", "2026-11-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value("2026-11-01"))
                .andExpect(jsonPath("$.data.tripCount").value(0))
                .andExpect(jsonPath("$.data.tripsByStatus.IN_TRANSIT").value(0))
                .andExpect(jsonPath("$.data.fill.averagePercent").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.fill.planCount").value(0))
                .andExpect(jsonPath("$.data.vehicles.total").value(0))
                .andExpect(jsonPath("$.data.recentTrips").isArray());
        verify(managerDashboardService).get(1L, FROM, TO, 10);
    }

    @Test
    @WithMockUser(roles = "DISPATCHER")
    void dispatcherIsAllowed() throws Exception {
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(managerDashboardService.get(1L, FROM, TO, 10))
                .thenReturn(ManagerDashboardCalculator.compute(FROM, TO, List.of(), List.of()));
        mockMvc.perform(get("/api/manager/dashboard").param("from", "2026-11-01").param("to", "2026-11-05"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "DISPATCHER")
    void limitIsPassedToService() throws Exception {
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(managerDashboardService.get(1L, FROM, TO, 30))
                .thenReturn(ManagerDashboardCalculator.compute(FROM, TO, List.of(), List.of()));
        mockMvc.perform(get("/api/manager/dashboard")
                        .param("from", "2026-11-01").param("to", "2026-11-05").param("limit", "30"))
                .andExpect(status().isOk());
        verify(managerDashboardService).get(1L, FROM, TO, 30);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void companyAdminIsForbidden() throws Exception {
        mockMvc.perform(get("/api/manager/dashboard").param("from", "2026-11-01").param("to", "2026-11-05"))
                .andExpect(status().isForbidden());
    }
}
