package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import java.util.Map;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class OptimizationClientContractTest {
    @Test void actualHttpClientSendsOnlyReservedIdentityAndServiceToken() {
        var builder=RestClient.builder().baseUrl("http://offline.test");
        var server=MockRestServiceServer.bindTo(builder).build();
        var token=mock(ServiceTokenProvider.class);
        when(token.getOptimizationServiceToken()).thenReturn("TEST_ONLY_SERVICE_TOKEN");
        server.expect(requestTo("http://offline.test/api/v1/optimization/jobs"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization","Bearer TEST_ONLY_SERVICE_TOKEN"))
            .andExpect(content().json("{\"trip_id\":7,\"job_uuid\":\"reserved-uuid\"}"))
            .andRespond(withSuccess("{\"success\":true,\"data\":{\"job_id\":\"reserved-uuid\"}}",MediaType.APPLICATION_JSON));
        var body=new OptimizationClient(builder.build(),token).createJob(Map.of("trip_id",7L,"job_uuid","reserved-uuid"));
        assertTrue(body.contains("reserved-uuid"));server.verify();
    }
}
