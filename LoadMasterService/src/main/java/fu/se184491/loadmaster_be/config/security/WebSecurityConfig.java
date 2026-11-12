package fu.se184491.loadmaster_be.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.FrontendUrls;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Import({
        RolePermissionMapping.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class
})
public class WebSecurityConfig {

    /** Origin được gọi API, ngăn bằng dấu phẩy (app.frontend.cors-origins). */
    @Value(FrontendUrls.CORS_ORIGINS)
    private String corsOrigins = FrontendUrls.DEFAULT;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            ObjectProvider<CurrentUserService> currentUserServiceProvider
    ) throws Exception {

        http
                .cors(cors ->
                        cors.configurationSource(corsConfigurationSource())
                )

                .csrf(AbstractHttpConfigurer::disable)

                .authorizeHttpRequests(auth -> auth

                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/api/vehicle-types",
                                "/api/auth/password-reset/**",
                                "/webhook/**",
                                "/api/payment/**",
                                "/api/guest-registration/**",
                                "/api/trial-registration/**",
                                "/actuator/health"
                        )
                        .permitAll()

                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/subscription/plans", "/api/subscription/plans/**")
                        .permitAll()

                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/contact-guests")
                        .permitAll()

                        .anyRequest()
                        .authenticated()
                )

                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)
                        )
                );

        // Bộ lọc tài khoản bị gắn cờ chỉ sống trong chuỗi bảo mật này, không phải bean: là bean thì Spring Boot đăng ký
        // nó thêm một lần làm servlet filter thường, và lát cắt @WebMvcTest kéo nó vào mà không có CurrentUserService.
        CurrentUserService currentUserService = currentUserServiceProvider.getIfAvailable();
        if (currentUserService != null) {
            http.addFilterAfter(
                    new FlaggedUserEnforcementFilter(currentUserService, new ObjectMapper()),
                    BearerTokenAuthenticationFilter.class
            );
        } else {
            log.warn("CurrentUserService is not available; FlaggedUserEnforcementFilter is not added to the security chain");
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration =
                new CorsConfiguration();

        configuration.setAllowedOrigins(
                FrontendUrls.origins(corsOrigins)
        );

        configuration.setAllowedMethods(
                List.of(
                        "GET",
                        "POST",
                        "PUT",
                        "PATCH",
                        "DELETE",
                        "OPTIONS"
                )
        );

        configuration.setAllowedHeaders(
                List.of("*")
        );

        configuration.setExposedHeaders(
                List.of(
                        "Authorization"
                )
        );

        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/**",
                configuration
        );

        return source;
    }
}