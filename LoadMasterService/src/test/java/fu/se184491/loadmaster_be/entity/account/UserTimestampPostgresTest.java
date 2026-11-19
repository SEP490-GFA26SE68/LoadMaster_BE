package fu.se184491.loadmaster_be.entity.account;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.schema.TargetType;
import org.hibernate.tool.schema.internal.ExceptionHandlerHaltImpl;
import org.hibernate.tool.schema.internal.exec.ScriptTargetOutputToWriter;
import org.hibernate.tool.schema.spi.ContributableMatcher;
import org.hibernate.tool.schema.spi.SchemaManagementTool;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.hibernate.tool.schema.spi.ScriptTargetOutput;
import org.hibernate.tool.schema.spi.TargetDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.StringWriter;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Only the newly created disposable container is reachable; no Spring application/profile is loaded. */
class UserTimestampPostgresTest {
    @Test
    void validatesV19LocalStringsRoundTripsSecondsAndGeneratesNoTypeAlter() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for PostgreSQL verification");
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            createFixture(postgres);
            Map<String, Object> settings = new HashMap<>();
            settings.put("hibernate.connection.url", postgres.getJdbcUrl());
            settings.put("hibernate.connection.username", postgres.getUsername());
            settings.put("hibernate.connection.password", postgres.getPassword());
            settings.put("hibernate.hbm2ddl.auto", "validate");
            settings.put("hibernate.jdbc.time_zone", "UTC");
            var registry = new StandardServiceRegistryBuilder().applySettings(settings).build();
            try {
                var metadata = new MetadataSources(registry)
                        .addAnnotatedClass(User.class).addAnnotatedClass(Company.class).buildMetadata();
                StringWriter ddl = new StringWriter();
                TargetDescriptor scriptOnly = new TargetDescriptor() {
                    public EnumSet<TargetType> getTargetTypes() { return EnumSet.of(TargetType.SCRIPT); }
                    public ScriptTargetOutput getScriptTargetOutput() { return new ScriptTargetOutputToWriter(ddl); }
                };
                registry.getService(SchemaManagementTool.class).getSchemaMigrator(settings).doMigration(
                        metadata,
                        SchemaManagementToolCoordinator.buildExecutionOptions(settings, ExceptionHandlerHaltImpl.INSTANCE),
                        ContributableMatcher.ALL, scriptOnly);

                // Building the factory validates the existing schema; it never creates or updates it.
                try (var factory = metadata.buildSessionFactory()) {
                    try (var session = factory.openSession()) {
                        session.beginTransaction();
                        session.createNativeMutationQuery("SET LOCAL TIME ZONE 'Asia/Ho_Chi_Minh'").executeUpdate();
                        User existing = session.find(User.class, 1L);
                        assertEquals(LocalDateTime.of(2026, 11, 4, 22, 0), existing.getCreatedAt());
                        assertEquals(LocalDateTime.of(2026, 11, 4, 22, 0, 1), existing.getUpdatedAt());
                        assertEquals(1L, session.createQuery("select count(u) from User u", Long.class).getSingleResult());

                        User user = User.builder().keycloakId("test-new").email("new@example.test")
                                .fullName("Test user").userRoleType(UserRole.DRIVER).status(UserStatus.ACTIVE).build();
                        session.persist(user);
                        session.flush();
                        Long id = user.getId();
                        LocalDateTime created = user.getCreatedAt();
                        assertEquals(created, user.getUpdatedAt());
                        assertEquals(0, created.getNano());
                        session.clear();
                        user = session.find(User.class, id);
                        assertEquals(created, user.getCreatedAt());
                        user.setFullName("Updated test user");
                        session.flush();
                        session.clear();
                        user = session.find(User.class, id);
                        assertEquals(created, user.getCreatedAt());
                        assertFalse(user.getUpdatedAt().isBefore(created));
                        assertEquals(0, user.getUpdatedAt().getNano());
                        session.getTransaction().commit();
                    }
                }
                try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("""
                             SELECT data_type FROM information_schema.columns
                             WHERE table_schema = 'public' AND table_name = 'users'
                               AND column_name IN ('created_at', 'updated_at')
                             """)) {
                    int count = 0;
                    while (rows.next()) {
                        assertEquals("character varying", rows.getString(1));
                        count++;
                    }
                    assertEquals(2, count);
                }
                // Keep this release assertion after round-trip checks so a schema mismatch cannot hide them.
                assertFalse(ddl.toString().toLowerCase(java.util.Locale.ROOT).contains("alter column"), ddl.toString());

            } finally {
                StandardServiceRegistryBuilder.destroy(registry);
            }
        }
    }

    @Test
    void inactiveUserCutoffQueryMustRemainCompatibleWithMappedTimestamp() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for PostgreSQL verification");
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("user_cutoff_test")) {
            postgres.start();
            createFixture(postgres);
            Map<String,Object> settings = new HashMap<>();
            settings.put("hibernate.connection.url",postgres.getJdbcUrl());
            settings.put("hibernate.connection.username",postgres.getUsername());
            settings.put("hibernate.connection.password",postgres.getPassword());
            settings.put("hibernate.hbm2ddl.auto","validate");
            var registry = new StandardServiceRegistryBuilder().applySettings(settings).build();
            try {
                var metadata = new MetadataSources(registry).addAnnotatedClass(User.class).addAnnotatedClass(Company.class).buildMetadata();
                try (var factory = metadata.buildSessionFactory(); var session = factory.openSession()) {
                    session.beginTransaction();
                    var repository = new JpaRepositoryFactory(session).getRepository(UserRepository.class);
                    // Query cutoff follows the current LocalDateTime/VARCHAR entity contract.
                    assertTrue(repository.findByStatusAndUpdatedAtBefore(UserStatus.ACTIVE, LocalDateTime.now().plusSeconds(10))
                            .stream().anyMatch(found -> Long.valueOf(1).equals(found.getId())));
                    session.getTransaction().rollback();
                }
            } finally { StandardServiceRegistryBuilder.destroy(registry); }
        }
    }

    private static void createFixture(PostgreSQLContainer postgres) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE companies (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        company_code VARCHAR(50) NOT NULL UNIQUE, company_name VARCHAR(255) NOT NULL,
                        tax_code VARCHAR(50) NOT NULL UNIQUE, billing_email VARCHAR(150) NOT NULL,
                        status VARCHAR(30) NOT NULL, created_at TIMESTAMPTZ)
                    """);
            statement.execute("""
                    CREATE TABLE users (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        keycloak_id VARCHAR(100) NOT NULL UNIQUE, company_id BIGINT REFERENCES companies(id),
                        email VARCHAR(150) NOT NULL UNIQUE, full_name VARCHAR(150) NOT NULL, phone_number VARCHAR(20),
                        user_role_type VARCHAR(30) NOT NULL, user_type VARCHAR(20), status VARCHAR(30) NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT chk_users_updated_after_created CHECK (updated_at >= created_at))
                    """);
            statement.execute("""
                    INSERT INTO users(keycloak_id,email,full_name,user_role_type,status,created_at,updated_at)
                    VALUES ('test-existing','existing@example.test','Existing user','DRIVER','ACTIVE',
                            '2026-11-04T22:00:00.123456+07','2026-11-04T22:00:01.654321+07')
                    """);
            try (var input = UserTimestampPostgresTest.class.getResourceAsStream("/db/migration/V19__format_users_created_updated_at.sql")) {
                assertNotNull(input);
                statement.execute(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }
}
