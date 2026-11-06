package fu.se184491.loadmaster_be.entity.account;

import fu.se184491.loadmaster_be.entity.company.Company;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class UserTimestampTest {
    @Test
    void persistInitializesBothTimestampsToTheSameCurrentSecond() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        User user = new User();
        user.prePersist();
        Instant after = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        assertEquals(user.getCreatedAt(), user.getUpdatedAt());
        assertEquals(0, user.getCreatedAt().getNano());
        assertFalse(user.getCreatedAt().isBefore(before));
        assertFalse(user.getCreatedAt().isAfter(after));
    }

    @Test
    void persistPreservesSuppliedCreationTimeAtSecondPrecision() {
        User user = User.builder().createdAt(Instant.parse("2026-11-04T15:00:00.999999Z")).build();
        user.prePersist();
        assertEquals(Instant.parse("2026-11-04T15:00:00Z"), user.getCreatedAt());
        assertEquals(user.getCreatedAt(), user.getUpdatedAt());
    }

    @Test
    void updateChangesOnlyUpdatedAtAndNeverPrecedesCreation() {
        Instant created = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        User user = User.builder().createdAt(created).updatedAt(created).build();
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        user.preUpdate();
        assertEquals(created, user.getCreatedAt());
        assertFalse(user.getUpdatedAt().isBefore(before));
        assertEquals(0, user.getUpdatedAt().getNano());

        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        user.setCreatedAt(future);
        user.preUpdate();
        assertEquals(future, user.getCreatedAt());
        assertEquals(future, user.getUpdatedAt());
    }

    @Test
    void postgresMetadataUsesNativeTimestampWithTimeZoneWithoutStringConverter() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .build();
        try {
            var metadata = new MetadataSources(registry)
                    .addAnnotatedClass(User.class).addAnnotatedClass(Company.class).buildMetadata();
            var binding = metadata.getEntityBinding(User.class.getName());
            for (String name : new String[]{"createdAt", "updatedAt"}) {
                var property = binding.getProperty(name);
                var column = (org.hibernate.mapping.Column) property.getSelectables().getFirst();
                assertEquals(Instant.class, property.getType().getReturnedClass());
                assertEquals(SqlTypes.TIMESTAMP_WITH_TIMEZONE, column.getSqlTypeCode(metadata));
                assertTrue(column.getSqlType(metadata).contains("with time zone"));
                assertTrue(column.getCheckConstraints().isEmpty(), "No string-format check may remain");
                assertFalse(column.isNullable());
            }
            assertFalse(binding.getProperty("createdAt").isUpdateable());
            assertTrue(binding.getProperty("updatedAt").isUpdateable());
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
