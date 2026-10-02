package fu.se184491.loadmaster_be.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient optimizationRestClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:8000")
                .build();
    }

    @Bean
    public RestClient keycloakAdminRestClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:8180")
                .build();
    }

    @Bean
    public RestClient brevoRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.brevo.com")
                .build();
    }

    @Bean
    public RestClient goongRestClient(
            @Value("${goong.api.base-url}") String baseUrl,
            @Value("${goong.api.timeout-seconds}") long timeoutSeconds
    ) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
