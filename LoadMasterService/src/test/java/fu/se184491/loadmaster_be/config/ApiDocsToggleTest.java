package fu.se184491.loadmaster_be.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Swagger UI và /v3/api-docs chỉ mở khi bật profile dev hoặc đặt API_DOCS_ENABLED=true. */
class ApiDocsToggleTest {

    private static final List<String> KEYS = List.of("springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled");

    @Test
    void apiDocsAreOffByDefault() throws IOException {
        StandardEnvironment env = environment(Map.of(), "application.yml");

        KEYS.forEach(key -> assertThat(env.getProperty(key, Boolean.class)).as(key).isFalse());
    }

    @Test
    void devProfileTurnsApiDocsOn() throws IOException {
        StandardEnvironment env = environment(Map.of(), "application-dev.yml", "application.yml");

        KEYS.forEach(key -> assertThat(env.getProperty(key, Boolean.class)).as(key).isTrue());
    }

    @Test
    void variableTurnsApiDocsOnWithoutDevProfile() throws IOException {
        StandardEnvironment env = environment(Map.of("API_DOCS_ENABLED", "true"), "application.yml");

        KEYS.forEach(key -> assertThat(env.getProperty(key, Boolean.class)).as(key).isTrue());
    }

    @Test
    void variableTurnsApiDocsOffInDevProfile() throws IOException {
        StandardEnvironment env = environment(Map.of("API_DOCS_ENABLED", "false"), "application-dev.yml",
                "application.yml");

        KEYS.forEach(key -> assertThat(env.getProperty(key, Boolean.class)).as(key).isFalse());
    }

    /** Các file theo thứ tự ưu tiên giảm dần; biến môi trường thật của máy chạy test bị bỏ qua. */
    private static StandardEnvironment environment(Map<String, Object> variables, String... files)
            throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new MapPropertySource("test-variables", variables));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String file : files) {
            for (PropertySource<?> source : loader.load(file, new ClassPathResource(file))) {
                sources.addLast(source);
            }
        }
        return env;
    }
}
