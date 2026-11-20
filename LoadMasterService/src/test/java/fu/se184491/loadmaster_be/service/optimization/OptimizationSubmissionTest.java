package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.constant.optimization.*;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.exception.InsufficientCreditsException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class OptimizationSubmissionTest {
    private final OptimizationAdmissionService admission = mock(OptimizationAdmissionService.class);
    private final OptimizationClient client = mock(OptimizationClient.class);
    private final OptimizationSubmissionService service = new OptimizationSubmissionService(admission,client);
    @Test void failedAdmissionNeverCallsPython() {
        when(admission.reserve(7L,null,OptimizationObjective.MAX_VOLUME,60,null,"key"))
                .thenThrow(new InsufficientCreditsException());
        assertThrows(InsufficientCreditsException.class,()->service.submitInitial(7L,"key"));
        verifyNoInteractions(client);
    }
    @Test void dispatchLossReturnsDurableJobReferenceForRetry() {
        when(admission.reserve(7L,null,OptimizationObjective.MAX_VOLUME,60,null,"key"))
                .thenReturn(OptimizationJob.builder().jobUuid("job-1").status(OptimizationJobStatus.PENDING).build());
        when(client.createJob(any())).thenThrow(new IllegalStateException("lost response"));
        var result = service.submitInitial(7L,"key");
        assertEquals("job-1",result.getData().get("job_id"));
        assertEquals("UNKNOWN",result.getData().get("dispatch_status"));
    }
    @Test void requestSendsOnlyServerJobIdentityNotClientPricing() {
        when(admission.reserve(7L,null,OptimizationObjective.MAX_VOLUME,60,null,"key"))
                .thenReturn(OptimizationJob.builder().jobUuid("job-1").status(OptimizationJobStatus.PENDING).build());
        service.submitInitial(7L,"key");
        verify(client).createJob(Map.of("trip_id",7L,"job_uuid","job-1"));
    }
    @Test void alreadyRunningRetryDoesNotDispatchAgain() {
        when(admission.reserve(7L,null,OptimizationObjective.MAX_VOLUME,60,null,"key"))
                .thenReturn(OptimizationJob.builder().jobUuid("job-1").status(OptimizationJobStatus.RUNNING).build());
        assertEquals("ALREADY_ACCEPTED",service.submitInitial(7L,"key").getData().get("dispatch_status"));
        verifyNoInteractions(client);
    }
}
