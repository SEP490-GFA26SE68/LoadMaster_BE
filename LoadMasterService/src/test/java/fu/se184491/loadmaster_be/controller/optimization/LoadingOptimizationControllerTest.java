package fu.se184491.loadmaster_be.controller.optimization;

import fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler;
import fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStatusResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import fu.se184491.loadmaster_be.service.optimization.LoadingOptimizationService;
import org.junit.jupiter.api.DisplayName;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LoadingOptimizationController.class)
@Import({WebSecurityConfig.class, fu.se184491.loadmaster_be.service.optimization.OptimizationAccess.class})
@AutoConfigureMockMvc(addFilters = false)
class LoadingOptimizationControllerTest {

    @MockitoBean private fu.se184491.loadmaster_be.service.optimization.OptimizationSubmissionService submission;
    @MockitoBean private fu.se184491.loadmaster_be.helpers.CurrentUserService principal;
    @MockitoBean private fu.se184491.loadmaster_be.repository.optimization.OptimizationJobRepository jobs;
    @MockitoBean private fu.se184491.loadmaster_be.repository.trip.TripRepository trips;
    @MockitoBean private fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository placements;
    @MockitoBean private fu.se184491.loadmaster_be.repository.account.UserRepository users;

    @org.junit.jupiter.api.BeforeEach
    void tenantFixture() {
        var company = new fu.se184491.loadmaster_be.entity.company.Company(); company.setId(10L);
        var trip = new fu.se184491.loadmaster_be.entity.trip.Trip(); trip.setId(10L); trip.setCompany(company);
        var job = new fu.se184491.loadmaster_be.entity.optimization.OptimizationJob(); job.setTrip(trip); job.setJobUuid(JOB);
        when(principal.getCurrentCompanyId()).thenReturn(10L);
        when(jobs.findByJobUuid(JOB)).thenReturn(java.util.Optional.of(job));
    }

    private static final String JOB = "0f8c2b9e-1111-4222-8333-944455556666";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoadingOptimizationService loadingOptimizationService;

    @MockitoBean
    private LoadPlanQueryService loadPlanQueryService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private RolePermissionMapping rolePermissionMapping;

    @MockitoBean
    private RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private RestAccessDeniedHandler accessDeniedHandler;

    @Test
    @DisplayName("GET /api/trips/optimization-jobs/{jobId}: phong bì ApiResponse, trường camelCase")
    @WithMockUser(roles = "DISPATCHER")
    void jobStatusIsAnEnvelopeInCamelCase() throws Exception {
        when(loadingOptimizationService.getJobStatus(JOB)).thenReturn(OptimizationJobStatusResponse.builder()
                .id(42L)
                .jobId(JOB)
                .tripId(10L)
                .objective("MAX_VOLUME")
                .timeLimitSec(60)
                .status("PARTIAL")
                .computationMs(812L)
                .createdAt(Instant.parse("2026-11-05T03:15:00Z"))
                .packedPlanCount(3)
                .build());

        mockMvc.perform(get("/api/trips/optimization-jobs/" + JOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.jobId").value(JOB))
                .andExpect(jsonPath("$.data.tripId").value(10))
                .andExpect(jsonPath("$.data.objective").value("MAX_VOLUME"))
                .andExpect(jsonPath("$.data.timeLimitSec").value(60))
                .andExpect(jsonPath("$.data.status").value("PARTIAL"))
                .andExpect(jsonPath("$.data.computationMs").value(812))
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-05T03:15:00Z"))
                .andExpect(jsonPath("$.data.packedPlanCount").value(3))
                .andExpect(jsonPath("$.data.job_uuid").doesNotExist())
                .andExpect(jsonPath("$.data.time_limit_sec").doesNotExist());
    }

    @Test
    @DisplayName("Job của công ty khác: 404 có mã OPTIMIZATION_JOB_NOT_FOUND")
    @WithMockUser(roles = "MANAGER")
    void jobOfAnotherCompanyIsNotFoundWithCode() throws Exception {
        when(loadingOptimizationService.getJobStatus(JOB))
                .thenThrow(new AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND));

        mockMvc.perform(get("/api/trips/optimization-jobs/" + JOB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("OPTIMIZATION_JOB_NOT_FOUND"));
    }
}
