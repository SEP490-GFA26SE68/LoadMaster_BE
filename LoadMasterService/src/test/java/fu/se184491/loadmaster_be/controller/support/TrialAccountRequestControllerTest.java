package fu.se184491.loadmaster_be.controller.support;

import fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler;
import fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.response.support.TrialAccountRequestResponse;
import fu.se184491.loadmaster_be.exception.GlobalExceptionHandler;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.service.support.TrialAccountRequestService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TrialAccountRequestController.class)
@Import({WebSecurityConfig.class, RolePermissionMapping.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class TrialAccountRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TrialAccountRequestService trialAccountRequestService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private RestAccessDeniedHandler accessDeniedHandler;

    @Test
    @DisplayName("POST /api/trial-requests - Allows SYSTEM_SUPPORTER to forward trial request")
    @WithMockUser(roles = "SYSTEM_SUPPORTER")
    void createTrialRequest_whenSystemSupporter_allowsAndCallsService() throws Exception {
        String body = """
                {
                  "companyName": "Trial Co",
                  "taxCode": "0109998887",
                  "fullName": "Nguyen Van A",
                  "email": "trial@co.vn",
                  "phoneNumber": "0912345678"
                }
                """;

        when(trialAccountRequestService.createTrialRequest(any(), any()))
                .thenReturn(TrialAccountRequestResponse.builder().id(10L).companyName("Trial Co").build());

        mockMvc.perform(post("/api/trial-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        verify(trialAccountRequestService).createTrialRequest(any(), any());
    }

    @Test
    @DisplayName("POST /api/trial-requests - Denies COMPANY_ADMIN with 403 Forbidden")
    @WithMockUser(roles = "COMPANY_ADMIN")
    void createTrialRequest_whenCompanyAdmin_returnsForbidden() throws Exception {
        String body = """
                {
                  "companyName": "Trial Co",
                  "taxCode": "0109998887",
                  "fullName": "Nguyen Van A",
                  "email": "trial@co.vn",
                  "phoneNumber": "0912345678"
                }
                """;

        mockMvc.perform(post("/api/trial-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        verify(trialAccountRequestService, never()).createTrialRequest(any(), any());
    }

    @Test
    @DisplayName("GET /api/trial-requests & POST /api/trial-requests/{id}/approve - Allows SYSTEM_MANAGER")
    @WithMockUser(roles = "SYSTEM_MANAGER")
    void trialRequests_whenCallerIsSystemManager_allowsAccess() throws Exception {
        when(trialAccountRequestService.getTrialRequests(any(), any(), any()))
                .thenReturn(PageResponse.<TrialAccountRequestResponse>builder().data(List.of()).build());
        when(trialAccountRequestService.approveTrialRequest(any(), any(), any()))
                .thenReturn(TrialAccountRequestResponse.builder().id(1L).build());

        mockMvc.perform(get("/api/trial-requests"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/trial-requests/1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/trial-requests/{id}/approve - Denies SYSTEM_SUPPORTER with 403 Forbidden")
    @WithMockUser(roles = "SYSTEM_SUPPORTER")
    void approveTrialRequest_whenCallerIsSystemSupporter_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/trial-requests/1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(trialAccountRequestService, never()).approveTrialRequest(any(), any(), any());
    }

    @Test
    @DisplayName("GET /api/trial-requests & POST /api/trial-requests/{id}/approve - Allows SYSTEM_ADMIN")
    @WithMockUser(roles = "SYSTEM_ADMIN")
    void trialRequests_whenCallerIsSystemAdmin_returnsOk() throws Exception {
        when(trialAccountRequestService.getTrialRequests(any(), any(), any()))
                .thenReturn(PageResponse.<TrialAccountRequestResponse>builder().data(List.of()).build());
        when(trialAccountRequestService.approveTrialRequest(any(), any(), any()))
                .thenReturn(TrialAccountRequestResponse.builder().id(1L).build());

        mockMvc.perform(get("/api/trial-requests"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/trial-requests/1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }
}
