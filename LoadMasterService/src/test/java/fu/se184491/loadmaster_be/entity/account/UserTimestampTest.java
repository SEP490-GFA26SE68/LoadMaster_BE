package fu.se184491.loadmaster_be.entity.account;

import fu.se184491.loadmaster_be.entity.company.Company;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Current source is a local wall-clock contract; these fixtures deliberately carry no zone. */
class UserTimestampTest {
    @Test
    void converterRoundTripsLocalSecondsWithoutInventingAnOffset() {
        var converter = new fu.se184491.loadmaster_be.entity.support.SupportTicket.LocalDateTimeSecondsConverter();
        var local = LocalDateTime.parse("2026-11-04T22:00:00.999999");
        assertEquals("2026-11-04 22:00:00", converter.convertToDatabaseColumn(local));
        assertEquals(local.truncatedTo(ChronoUnit.SECONDS), converter.convertToEntityAttribute("2026-11-04 22:00:00"));
        assertEquals(local.truncatedTo(ChronoUnit.SECONDS), converter.convertToEntityAttribute("2026-11-04T22:00:00"));
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void persistInitializesBothTimestampsToTheSameCurrentSecond() {
        LocalDateTime before = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        User user = new User();
        user.prePersist();
        LocalDateTime after = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        assertEquals(user.getCreatedAt(), user.getUpdatedAt());
        assertEquals(0, user.getCreatedAt().getNano());
        assertFalse(user.getCreatedAt().isBefore(before));
        assertFalse(user.getCreatedAt().isAfter(after));
    }

    @Test
    void persistPreservesSuppliedCreationTimeAtSecondPrecision() {
        User user = User.builder().createdAt(LocalDateTime.parse("2026-11-04T15:00:00.999999")).build();
        user.prePersist();
        assertEquals(LocalDateTime.parse("2026-11-04T15:00:00"), user.getCreatedAt());
        assertEquals(user.getCreatedAt(), user.getUpdatedAt());
    }

    @Test
    void updateChangesOnlyUpdatedAtAndNeverPrecedesCreation() {
        LocalDateTime created = LocalDateTime.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        User user = User.builder().createdAt(created).updatedAt(created).build();
        LocalDateTime before = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        user.preUpdate();
        assertEquals(created, user.getCreatedAt());
        assertFalse(user.getUpdatedAt().isBefore(before));
        assertEquals(0, user.getUpdatedAt().getNano());

        LocalDateTime future = LocalDateTime.now().plus(1, ChronoUnit.DAYS);
        user.setCreatedAt(future);
        user.preUpdate();
        assertEquals(future, user.getCreatedAt());
        assertEquals(future, user.getUpdatedAt());
    }

    @Test
    void postgresMetadataMatchesCurrentLocalStringMapping() {
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
                assertEquals(LocalDateTime.class, property.getType().getReturnedClass());
                assertEquals(SqlTypes.VARCHAR, column.getSqlTypeCode(metadata));
                assertEquals(19L, column.getLength());
                assertEquals("varchar(19)", column.getSqlType(metadata));
                assertFalse(column.getCheckConstraints().isEmpty(), "Current local-string contract requires a format check");
                assertFalse(column.isNullable());
            }
            assertFalse(binding.getProperty("createdAt").isUpdateable());
            assertTrue(binding.getProperty("updatedAt").isUpdateable());
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
