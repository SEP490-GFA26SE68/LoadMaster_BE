package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.constant.optimization.*;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OptimizationDispatchContractTest {
    static final String ID="2c278edd-70ca-46c8-a5a7-35ad409a00cc";
    final OptimizationAdmissionService admission=mock(OptimizationAdmissionService.class);
    final OptimizationClient client=mock(OptimizationClient.class);
    final OptimizationSubmissionService service=new OptimizationSubmissionService(admission,client);

    void pending() {
        when(admission.reserve(7L,null,OptimizationObjective.MAX_VOLUME,60,null,"key"))
            .thenReturn(OptimizationJob.builder().jobUuid(ID).status(OptimizationJobStatus.PENDING).build());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={
        "null", "{}", "not-json", "{\"success\":true}",
        "{\"success\":true,\"data\":{}}",
        "{\"success\":true,\"data\":{\"job_id\":123}}",
        "{\"success\":true,\"data\":{\"job_id\":\"other-job\"}}",
        "{\"success\":false,\"data\":{\"job_id\":\"2c278edd-70ca-46c8-a5a7-35ad409a00cc\"}}",
        "{\"success\":\"true\",\"data\":{\"job_id\":\"2c278edd-70ca-46c8-a5a7-35ad409a00cc\"}}"
    })
    void invalidAcknowledgementKeepsReservedIdentityUnknown(String body) {
        pending();when(client.createJob(any())).thenReturn(body);
        var data=service.submitInitial(7L,"key").getData();
        assertEquals(ID,data.get("job_id"));assertEquals("UNKNOWN",data.get("dispatch_status"));
        verify(admission,times(1)).reserve(7L,null,OptimizationObjective.MAX_VOLUME,60,null,"key");
    }
    @ParameterizedTest @CsvSource({"PENDING,ACCEPTED","RUNNING,ALREADY_RUNNING",
        "COMPLETED,ALREADY_TERMINAL","PARTIAL,ALREADY_TERMINAL","NO_SOLUTION,ALREADY_TERMINAL",
        "TIMEOUT,ALREADY_TERMINAL","FAILED,ALREADY_TERMINAL","SUCCESS,ALREADY_TERMINAL"})
    void validatedAcknowledgementAcceptsExactJobAndStatus(String status,String state) {
        pending();when(client.createJob(any())).thenReturn("{\"success\":true,\"data\":{\"job_id\":\""+ID+
            "\",\"status\":\""+status+"\",\"dispatch_state\":\""+state+"\"}}");
        assertEquals(status.equals("PENDING") ? "ACCEPTED" : "ALREADY_ACCEPTED",
            service.submitInitial(7L,"key").getData().get("dispatch_status"));
    }
    @Test void legacyAcknowledgementStillWorks() {
        pending();when(client.createJob(any())).thenReturn("{\"success\":true,\"data\":{\"job_id\":\""+ID+"\"}}");
        assertEquals("ACCEPTED",service.submitInitial(7L,"key").getData().get("dispatch_status"));
    }
    @Test void inconsistentStateIsUnknown() {
        pending();when(client.createJob(any())).thenReturn("{\"success\":true,\"data\":{\"job_id\":\""+ID+
            "\",\"status\":\"RUNNING\",\"dispatch_state\":\"ACCEPTED\"}}");
        assertEquals("UNKNOWN",service.submitInitial(7L,"key").getData().get("dispatch_status"));
    }
}
