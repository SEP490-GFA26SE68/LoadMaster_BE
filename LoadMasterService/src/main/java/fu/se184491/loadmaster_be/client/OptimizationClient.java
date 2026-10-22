package fu.se184491.loadmaster_be.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OptimizationClient {

    private final RestClient optimizationRestClient;
    private final ServiceTokenProvider serviceTokenProvider;

    public OptimizationClient(
            @Qualifier("optimizationRestClient")
            RestClient optimizationRestClient,
            ServiceTokenProvider serviceTokenProvider
    ) {
        this.optimizationRestClient = optimizationRestClient;
        this.serviceTokenProvider = serviceTokenProvider;
    }



    public String createJob(Object request) {

        try {
            ObjectMapper objectMapper = new ObjectMapper();

            String jsonBody = objectMapper.writeValueAsString(request);

            System.out.println("[Optimization] JSON = " + jsonBody);

            String token =
                    serviceTokenProvider.getOptimizationServiceToken();

            return optimizationRestClient
                    .post()
                    .uri("/api/v1/optimization/jobs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(token))
                    .body(jsonBody)
                    .retrieve()
                    .body(String.class);

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(
                    "Cannot serialize optimization request",
                    e
            );
        }
    }


    public String getJobStatus(String jobId) {

        String token =
                serviceTokenProvider.getOptimizationServiceToken();

        return optimizationRestClient
                .get()
                .uri("/api/v1/optimization/jobs/{id}", jobId)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(String.class);
    }



}