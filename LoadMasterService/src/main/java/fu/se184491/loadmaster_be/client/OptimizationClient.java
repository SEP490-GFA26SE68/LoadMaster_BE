package fu.se184491.loadmaster_be.client;

import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class OptimizationClient {

    private final RestClient optimizationRestClient;
    private final ServiceTokenProvider serviceTokenProvider;

    public String createJob(Object request) {

        String token =
                serviceTokenProvider.getOptimizationServiceToken();

        return optimizationRestClient
                .post()
                .uri("/api/v1/optimization/jobs")
                .headers(headers ->
                        headers.setBearerAuth(token)
                )
                .body(request)
                .retrieve()
                .body(String.class);
    }
}