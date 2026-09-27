package fu.se184491.loadmaster_be.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

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
}