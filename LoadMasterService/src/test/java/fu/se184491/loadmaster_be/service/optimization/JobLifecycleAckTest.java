package fu.se184491.loadmaster_be.service.optimization;
import fu.se184491.loadmaster_be.client.OptimizationDispatchAcknowledgement;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class JobLifecycleAckTest {
    static final String UUID="41858661-1c23-372e-9329-e09660e2648b";
    String body(String fields){return "{\"success\":true,\"data\":{\"job_id\":\""+UUID+"\",\"status\":\"RUNNING\",\"dispatch_state\":\"ALREADY_RUNNING\","+fields+"}}";}
    @Test void requiresVersionAndPositiveGenerationForRunning() {
        assertNotNull(OptimizationDispatchAcknowledgement.verifyV2(body("\"protocol_version\":2,\"execution_generation\":1"),UUID));
        for(String fields:new String[]{"\"protocol_version\":1,\"execution_generation\":1","\"protocol_version\":2,\"execution_generation\":0","\"protocol_version\":2,\"execution_generation\":\"1\"","\"execution_generation\":1"})
            assertThrows(IllegalStateException.class,()->OptimizationDispatchAcknowledgement.verifyV2(body(fields),UUID));
        assertThrows(IllegalStateException.class,()->OptimizationDispatchAcknowledgement.verifyV2(body("\"protocol_version\":2,\"execution_generation\":1"),"another-job"));
    }

    @Test void v2NeverAcceptsLegacyBodyWithoutCanonicalStatus() {
        String missing="{\"success\":true,\"data\":{\"job_id\":\""+UUID+"\",\"protocol_version\":2,\"execution_generation\":1}}";
        assertThrows(IllegalStateException.class,()->OptimizationDispatchAcknowledgement.verifyV2(missing,UUID));
        assertThrows(IllegalStateException.class,()->OptimizationDispatchAcknowledgement.verifyV2(body("\"protocol_version\":4294967298,\"execution_generation\":1"),UUID));
    }
}
