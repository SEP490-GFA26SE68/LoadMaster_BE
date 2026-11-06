package fu.se184491.loadmaster_be.controller.audit;

import fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler;
import fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.response.audit.AuditLogResponse;
import fu.se184491.loadmaster_be.service.audit.AuditLogFilter;
import fu.se184491.loadmaster_be.service.audit.AuditLogQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditLogController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class AuditLogControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AuditLogQueryService auditLogQueryService;
    @MockitoBean private JwtAuthenticationConverter jwtAuthenticationConverter;
    @MockitoBean private RolePermissionMapping rolePermissionMapping;
    @MockitoBean private RestAuthenticationEntryPoint authenticationEntryPoint;
    @MockitoBean private RestAccessDeniedHandler accessDeniedHandler;

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    void systemAdminReadsPagedLogsInCamelCase() throws Exception {
        AuditLogResponse row = AuditLogResponse.builder()
                .id(5L).actionType("TRIP_STATUS_TRANSITION").entityName("Trip").entityId("9")
                .userId(7L).userName("Nguyễn An").userRole("DISPATCHER")
                .companyId(1L).companyName("Long Bình")
                .oldValues(Map.of("status", "DRAFT")).newValues(Map.of("status", "PLANNED"))
                .createdAt(Instant.parse("2026-11-05T03:15:00Z"))
                .build();
        when(auditLogQueryService.search(any(), any(), anyInt(), anyInt())).thenReturn(PageResponse.<AuditLogResponse>builder()
                .success(true).data(List.of(row)).page(0).size(20).totalElements(1).totalPages(1).last(true).build());

        mockMvc.perform(get("/api/audit-logs")
                        .param("from", "2026-11-01").param("to", "2026-11-05")
                        .param("companyId", "platform").param("actionType", "TRIP_STATUS_TRANSITION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].actionType").value("TRIP_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.data[0].userName").value("Nguyễn An"))
                .andExpect(jsonPath("$.data[0].companyName").value("Long Bình"))
                .andExpect(jsonPath("$.data[0].oldValues.status").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-11-05T03:15:00Z"));

        verify(auditLogQueryService).search(
                eq(new AuditLogFilter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 5),
                        "TRIP_STATUS_TRANSITION", null, null, null)),
                eq("platform"), eq(0), eq(20));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void companyAdminIsAllowed() throws Exception {
        when(auditLogQueryService.search(any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.<AuditLogResponse>builder().success(true).data(List.of()).build());
        mockMvc.perform(get("/api/audit-logs")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "DISPATCHER")
    void otherRolesAreForbidden() throws Exception {
        mockMvc.perform(get("/api/audit-logs")).andExpect(status().isForbidden());
    }
}
