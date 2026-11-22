package fu.se184491.loadmaster_be.service.optimization;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OptimizationDiagnosticsTest {
    static final String JOB="90759730-d2d6-3220-ae53-39711097e703";
    static final String SECRET="SYNTHETIC_SECRET_TOKEN_PASSWORD_SQL_PARAMETER";
    Logger clientLog=(Logger)LoggerFactory.getLogger(OptimizationClient.class);
    Logger submissionLog=(Logger)LoggerFactory.getLogger(OptimizationSubmissionService.class);
    ListAppender<ILoggingEvent> capture=new ListAppender<>();
    ServiceTokenProvider tokens=mock(ServiceTokenProvider.class);
    OptimizationAdmissionService admission=mock(OptimizationAdmissionService.class);
    RestClient.Builder builder=RestClient.builder().baseUrl("http://diagnostics.invalid");
    MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    @BeforeEach void before(){
        capture.start();clientLog.addAppender(capture);submissionLog.addAppender(capture);
        when(tokens.getOptimizationServiceToken()).thenReturn(SECRET);
        when(admission.reserve(eq(12L),isNull(),any(),eq(60),isNull(),eq("same-key")))
                .thenReturn(OptimizationJob.builder().jobUuid(JOB).status(OptimizationJobStatus.PENDING).build());
    }
    @AfterEach void after(){clientLog.detachAppender(capture);submissionLog.detachAppender(capture);capture.stop();}
    String logs(){return capture.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",(a,b)->a+"\n"+b);}
    OptimizationSubmissionService service(){return new OptimizationSubmissionService(admission,new OptimizationClient(builder.build(),tokens));}
    @Test void remote500CorrelatesAndRedactsUnknownCodeAndMessage(){
        server.expect(requestTo("http://diagnostics.invalid/api/v1/optimization/jobs"))
            .andExpect(request -> assertNotNull(UUID.fromString(request.getHeaders().getFirst("X-Request-ID"))))
            .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"code\":\""+SECRET+"\",\"message\":\""+SECRET+"\"}"));
        assertEquals("UNKNOWN",service().submitInitial(12L,"same-key").getData().get("dispatch_status"));
        assertTrue(logs().contains("job_uuid="+JOB));assertTrue(logs().contains("trip_id=12"));
        assertTrue(logs().contains("remote_http_status=500"));assertTrue(logs().contains("remote_code=UNRECOGNIZED"));
        assertTrue(logs().contains("elapsed_ms="));assertFalse(logs().contains(SECRET));server.verify();
    }
    @Test void retryKeepsUuidAndUsesDistinctAttemptCorrelation(){
        List<String> ids=new ArrayList<>();
        for(int i=0;i<2;i++)server.expect(requestTo("http://diagnostics.invalid/api/v1/optimization/jobs"))
            .andExpect(content().json("{\"trip_id\":12,\"job_uuid\":\""+JOB+"\"}"))
            .andExpect(request -> ids.add(request.getHeaders().getFirst("X-Request-ID")))
            .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"INTERNAL_SERVER_ERROR\"}"));
        var service=service();
        for(int i=0;i<2;i++){
            var data=service.submitInitial(12L,"same-key").getData();
            assertEquals(JOB,data.get("job_id"));assertEquals("UNKNOWN",data.get("dispatch_status"));
        }
        assertEquals(2,ids.size());assertNotEquals(ids.get(0),ids.get(1));
        verify(admission,times(2)).reserve(eq(12L),isNull(),any(),eq(60),isNull(),eq("same-key"));
        assertFalse(logs().contains(SECRET));server.verify();
    }
    @Test void transportErrorNeverLogsMessageOrUrl(){
        server.expect(requestTo("http://diagnostics.invalid/api/v1/optimization/jobs"))
            .andRespond(request -> {throw new ResourceAccessException("postgres://user:"+SECRET+"@private.example/db");});
        assertEquals("UNKNOWN",service().submitInitial(12L,"same-key").getData().get("dispatch_status"));
        assertFalse(logs().contains(SECRET));assertFalse(logs().contains("private.example"));
        assertTrue(logs().contains("transport_error"));server.verify();
    }
    @Test void tokenFailureLogsOnlyTypeAndKeepsUnknown(){
        when(tokens.getOptimizationServiceToken()).thenThrow(new IllegalStateException(SECRET));
        assertEquals("UNKNOWN",service().submitInitial(12L,"same-key").getData().get("dispatch_status"));
        assertTrue(logs().contains("stage=service_token"));assertFalse(logs().contains(SECRET));
    }
}