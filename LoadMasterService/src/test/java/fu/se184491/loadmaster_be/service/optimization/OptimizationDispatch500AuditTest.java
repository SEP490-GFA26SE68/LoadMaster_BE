package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Synthetic HTTP only: no server, Spring context, database, real token, or credit mutation. */
class OptimizationDispatch500AuditTest {
    @Test void python500KeepsSameReservedJobAndUnknownDispatch() {
        var builder = RestClient.builder().baseUrl("http://audit.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        var tokens = mock(ServiceTokenProvider.class);
        when(tokens.getOptimizationServiceToken()).thenReturn("SYNTHETIC_TEST_ONLY");
        server.expect(requestTo("http://audit.invalid/api/v1/optimization/jobs"))
                .andExpect(content().json("{\"trip_id\":12,\"job_uuid\":\"synthetic-audit-job\"}"))
                .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"code\":\"INTERNAL_SERVER_ERROR\"}"));
        var admission = mock(OptimizationAdmissionService.class);
        when(admission.reserve(eq(12L), isNull(), any(), eq(60), isNull(), eq("same-action-key")))
                .thenReturn(OptimizationJob.builder().jobUuid("synthetic-audit-job")
                        .status(OptimizationJobStatus.PENDING).build());
        var service = new OptimizationSubmissionService(admission, new OptimizationClient(builder.build(), tokens));
        var data = service.submitInitial(12L, "same-action-key").getData();
        assertEquals("synthetic-audit-job", data.get("job_id"));
        assertEquals("UNKNOWN", data.get("dispatch_status"));
        verify(admission, times(1)).reserve(eq(12L), isNull(), any(), eq(60), isNull(), eq("same-action-key"));
        server.verify();
    }
}