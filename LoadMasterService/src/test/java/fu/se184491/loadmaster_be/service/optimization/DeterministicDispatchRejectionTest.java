package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.constant.optimization.*;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.exception.*;
import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DeterministicDispatchRejectionTest {
    static final String ID="cb12e122-1469-490b-ae8c-4203b82f24e9";
    static final String CODE="PACKING_PROFILE_ORIENTATION_INVALID";
    final OptimizationAdmissionService admission=mock(OptimizationAdmissionService.class);
    final RestClient.Builder builder=RestClient.builder().baseUrl("http://synthetic.invalid");
    final MockRestServiceServer remote=MockRestServiceServer.bindTo(builder).build();
    final ServiceTokenProvider tokens=mock(ServiceTokenProvider.class);
    final OptimizationClient client=new OptimizationClient(builder.build(),tokens);
    final OptimizationSubmissionService service=new OptimizationSubmissionService(admission,client);
    @BeforeEach void setup(){
        when(tokens.getOptimizationServiceToken()).thenReturn("SYNTHETIC_TOKEN_DO_NOT_LOG");
        when(admission.reserve(7L,null,OptimizationObjective.MAX_VOLUME,60,null,"key"))
            .thenReturn(OptimizationJob.builder().jobUuid(ID).status(OptimizationJobStatus.PENDING).build());
    }
    @Test void deterministic400PropagatesOriginalCodeWithoutRemoteMessage(){
        remote.expect(requestTo("http://synthetic.invalid/api/v1/optimization/jobs"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"code\":\""+CODE+"\",\"message\":\"SYNTHETIC_REMOTE_SECRET_PAYLOAD\"}"));
        var rejected=assertThrows(AppException.class,()->service.submitInitial(7L,"key"));
        assertEquals(ErrorCode.PACKING_PROFILE_ORIENTATION_INVALID,rejected.getErrorCode());
        assertFalse(rejected.getMessage().contains("SYNTHETIC_REMOTE_SECRET_PAYLOAD"));
        remote.verify();verify(admission,times(1)).reserve(7L,null,OptimizationObjective.MAX_VOLUME,60,null,"key");
    }
    static Stream<Arguments> ambiguousResponses(){
        String valid="{\"success\":false,\"code\":\""+CODE+"\"}";
        return Stream.of(Arguments.of(400,"{}"),Arguments.of(400,"not-json"),Arguments.of(400,"[]"),
            Arguments.of(400,"{\"success\":false,\"code\":\"UNRECOGNIZED\"}"),
            Arguments.of(400,"{\"code\":\""+CODE+"\"}"),
            Arguments.of(400,"{\"success\":true,\"code\":\""+CODE+"\"}"),
            Arguments.of(400,"{\"success\":\"false\",\"code\":\""+CODE+"\"}"),
            Arguments.of(401,valid),Arguments.of(403,valid),Arguments.of(422,valid),Arguments.of(500,valid),Arguments.of(503,valid));
    }
    @ParameterizedTest @MethodSource("ambiguousResponses")
    void nonAllowlistedOrUnprovenErrorKeepsUnknown(int status,String body){
        remote.expect(requestTo("http://synthetic.invalid/api/v1/optimization/jobs"))
            .andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
        var result=service.submitInitial(7L,"key").getData();
        assertEquals("UNKNOWN",result.get("dispatch_status"));assertEquals(ID,result.get("job_id"));remote.verify();
    }
    @ParameterizedTest @ValueSource(strings={"Read timed out","Connection reset","Response lost after ACK"})
    void transportLossStaysUnknown(String message){
        var mockClient=mock(OptimizationClient.class);
        when(mockClient.createJob(any())).thenThrow(new ResourceAccessException(message));
        var result=new OptimizationSubmissionService(admission,mockClient).submitInitial(7L,"key").getData();
        assertEquals("UNKNOWN",result.get("dispatch_status"));assertEquals(ID,result.get("job_id"));
    }
}
