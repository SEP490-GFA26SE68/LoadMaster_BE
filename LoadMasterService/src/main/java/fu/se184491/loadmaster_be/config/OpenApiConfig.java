package fu.se184491.loadmaster_be.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Value("${KEYCLOAK_AUTH_URL:http://localhost:8180}")
    private String keycloakAuthUrl;

    @Bean
    public OpenAPI customOpenAPI() {
        String tokenUrl = keycloakAuthUrl + "/realms/loadmaster/protocol/openid-connect/token";

        return new OpenAPI()
                .info(new Info()
                        .title("LoadMaster BE API")
                        .version("1.0")
                        .description("API Documentation for LoadMaster System")
                        .contact(new Contact().name("Development Team")))
                .addSecurityItem(new SecurityRequirement()
                        .addList("keycloakOAuth")
                        .addList("bearerAuth"))
                .components(new Components()
                        // 1. Đăng nhập trực tiếp bằng Username/Password để Swagger tự lấy JWT
                        .addSecuritySchemes("keycloakOAuth", new SecurityScheme()
                                .name("keycloakOAuth")
                                .type(SecurityScheme.Type.OAUTH2)
                                .description("Tự động đăng nhập Keycloak (nhập username / password để lấy JWT)")
                                .flows(new OAuthFlows()
                                        .password(new OAuthFlow()
                                                .tokenUrl(tokenUrl))))
                        // 2. Vẫn giữ Bearer HTTP nếu muốn dán JWT thủ công
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .name("bearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Hoặc dán trực tiếp token JWT vào đây")));
    }
}
