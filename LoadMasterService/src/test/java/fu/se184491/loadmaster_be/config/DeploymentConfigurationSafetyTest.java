package fu.se184491.loadmaster_be.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Loads ConfigData only: no application context, DataSource, scheduler or Flyway bean. */
class DeploymentConfigurationSafetyTest {
    private void verify(String profile) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("config-under-review", Map.of(
                "spring.config.location", Path.of("src/main/resources/application.yml").toAbsolutePath().toUri().toString())));
        if (profile != null) environment.setActiveProfiles(profile);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        assertEquals("false", environment.getProperty("spring.flyway.enabled"));
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
    }
    @Test void inheritedEnvironmentAndImportedConfigRemainSchemaReadOnly() { verify(null); }
    @Test void devProfileDoesNotEnableDdlOrFlyway() { verify("dev"); }
}
