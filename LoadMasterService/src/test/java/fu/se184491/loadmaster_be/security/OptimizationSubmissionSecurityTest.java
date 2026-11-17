package fu.se184491.loadmaster_be.security;

import fu.se184491.loadmaster_be.controller.optimization.LoadingOptimizationController;
import fu.se184491.loadmaster_be.service.optimization.*;
import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.constant.optimization.*;
import fu.se184491.loadmaster_be.exception.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LoadingOptimizationController.class)
@Import({TestJwtSupport.class,OptimizationSubmissionService.class})
class OptimizationSubmissionSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean LoadingOptimizationService loading;
    @MockitoBean LoadPlanQueryService plans;
    @MockitoBean OptimizationAdmissionService admission;
    @MockitoBean OptimizationAccess optimizationAccess;
    @MockitoBean OptimizationClient client;
    @Test void anonymousCannotCreateAnAction() throws Exception {
        mvc.perform(post("/api/trips/7/optimize-loading")).andExpect(status().isUnauthorized());
        verifyNoInteractions(admission,client);
    }
    @Test void driverCannotCreateAnAction() throws Exception {
        mvc.perform(post("/api/trips/7/optimize-loading").header("Idempotency-Key","driver-action")
            .header("Authorization","Bearer "+TestJwtSupport.userToken("u","DRIVER")))
            .andExpect(status().isForbidden());
        verifyNoInteractions(admission,client);
    }
    @Test void dispatcherPassesIdempotencyKeyAndReceivesSameContractJobId() throws Exception {
        when(admission.reserve(7L,null,OptimizationObjective.MAX_VOLUME,60,null,"retry-key"))
            .thenReturn(OptimizationJob.builder().jobUuid("authorized-job").status(OptimizationJobStatus.PENDING).build());
        mvc.perform(post("/api/trips/7/optimize-loading").header("Idempotency-Key","retry-key")
            .header("Authorization","Bearer "+TestJwtSupport.userToken("u","DISPATCHER")))
            .andExpect(status().isAccepted()).andExpect(header().string("Idempotency-Key","retry-key"))
            .andExpect(jsonPath("$.data.job_id").value("authorized-job"));
        verify(admission).reserve(7L,null,OptimizationObjective.MAX_VOLUME,60,null,"retry-key");
    }
    @Test void insufficientCreditsReturns402WithoutCallingPython() throws Exception {
        when(admission.reserve(anyLong(),isNull(),any(),anyInt(),isNull(),anyString()))
            .thenThrow(new InsufficientCreditsException());
        mvc.perform(post("/api/trips/7/optimize-loading").header("Idempotency-Key","poor")
            .header("Authorization","Bearer "+TestJwtSupport.userToken("u","DISPATCHER")))
            .andExpect(status().isPaymentRequired());
        verifyNoInteractions(client);
    }
    @Test void expiredSubscriptionReturns403WithoutCallingPython() throws Exception {
        when(admission.reserve(anyLong(),isNull(),any(),anyInt(),isNull(),anyString()))
            .thenThrow(new AppException(ErrorCode.OPTIMIZATION_ENTITLEMENT_REQUIRED));
        mvc.perform(post("/api/trips/7/optimize-loading").header("Idempotency-Key","expired")
            .header("Authorization","Bearer "+TestJwtSupport.userToken("u","DISPATCHER")))
            .andExpect(status().isForbidden());
        verifyNoInteractions(client);
    }
    @Test void deterministicRejectionUsesExisting400EnvelopeThroughSecurityFilters() throws Exception {
        when(admission.reserve(7L,null,OptimizationObjective.MAX_VOLUME,60,null,"orientation-key"))
            .thenReturn(OptimizationJob.builder().jobUuid("cb12e122-1469-490b-ae8c-4203b82f24e9").status(OptimizationJobStatus.PENDING).build());
        when(client.createJob(any())).thenThrow(new fu.se184491.loadmaster_be.client.OptimizationDispatchRejectedException());
        mvc.perform(post("/api/trips/7/optimize-loading").header("Idempotency-Key","orientation-key")
            .header("Authorization","Bearer "+TestJwtSupport.userToken("u","DISPATCHER")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("PACKING_PROFILE_ORIENTATION_INVALID"))
            .andExpect(jsonPath("$.data").doesNotExist());
        verify(admission,times(1)).reserve(7L,null,OptimizationObjective.MAX_VOLUME,60,null,"orientation-key");
    }
}
