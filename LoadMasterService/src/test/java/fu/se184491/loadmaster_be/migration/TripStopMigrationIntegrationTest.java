package fu.se184491.loadmaster_be.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TripStopMigrationIntegrationTest {

    @Test
    void migrationUpgradesLegacyTripsAndDeliveryStops() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the PostgreSQL migration integration test");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            createLegacySchema(postgres);

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .baselineOnMigrate(true)
                    .baselineVersion(MigrationVersion.fromVersion("0"))
                    .target(MigrationVersion.fromVersion("1"))
                    .load()
                    .migrate();

            try (Connection connection = connection(postgres)) {
                ColumnInfo latitude = column(connection, "delivery_stops", "latitude");
                ColumnInfo longitude = column(connection, "delivery_stops", "longitude");
                ColumnInfo stopStatus = column(connection, "delivery_stops", "status");
                ColumnInfo routePlan = column(connection, "trips", "route_plan");
                ColumnInfo handlingClassLock = column(connection, "trips", "handling_class_lock");
                ColumnInfo overrideReason = column(connection, "trips", "override_reason");

                assertAll(
                        () -> assertNumericCoordinate(latitude),
                        () -> assertNumericCoordinate(longitude),
                        () -> assertEquals(20, stopStatus.maximumLength()),
                        () -> assertEquals("NO", stopStatus.nullable()),
                        () -> assertTrue(stopStatus.defaultValue().contains("PENDING")),
                        () -> assertEquals("timestamp without time zone",
                                column(connection, "delivery_stops", "planned_arrival").dataType()),
                        () -> assertEquals("timestamp without time zone",
                                column(connection, "delivery_stops", "actual_arrival").dataType()),
                        () -> assertEquals("json", routePlan.dataType()),
                        () -> assertEquals(20, handlingClassLock.maximumLength()),
                        () -> assertEquals("text", overrideReason.dataType()),
                        () -> assertEquals(
                                List.of("DRAFT", "PLANNED", "PLANNED", "IN_TRANSIT", "IN_TRANSIT", "DELIVERED", "DELIVERED"),
                                values(connection, "SELECT status FROM trips ORDER BY id")
                        ),
                        () -> assertEquals("COMPLETED",
                                scalar(connection, "SELECT status FROM delivery_stops WHERE id = 1")),
                        () -> assertEquals("PENDING",
                                scalar(connection, "INSERT INTO delivery_stops DEFAULT VALUES RETURNING status")),
                        () -> assertThrows(SQLException.class,
                                () -> execute(connection, "INSERT INTO trips(status) VALUES ('UNKNOWN')")),
                        () -> assertThrows(SQLException.class,
                                () -> execute(connection, "INSERT INTO delivery_stops(status) VALUES ('SKIPPED')"))
                );
            }
        }
    }

    private static void createLegacySchema(PostgreSQLContainer postgres) throws SQLException {
        try (Connection connection = connection(postgres); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE trips (id BIGSERIAL PRIMARY KEY, status VARCHAR(30))");
            statement.execute("CREATE TABLE delivery_stops (id BIGSERIAL PRIMARY KEY, status VARCHAR(30))");
            statement.execute("""
                    INSERT INTO trips(status) VALUES
                    ('DRAFT'), ('OPTIMIZED'), ('APPROVED'), ('READY_FOR_DELIVERY'),
                    ('IN_TRANSIT'), ('DELIVERED'), ('COMPLETED')
                    """);
            statement.execute("INSERT INTO delivery_stops(status) VALUES ('SKIPPED')");
        }
    }

    private static Connection connection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static ColumnInfo column(Connection connection, String table, String column) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT data_type, numeric_precision, numeric_scale, character_maximum_length,
                       is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), () -> "Missing column " + table + "." + column);
                return new ColumnInfo(
                        result.getString("data_type"),
                        (Integer) result.getObject("numeric_precision"),
                        (Integer) result.getObject("numeric_scale"),
                        (Integer) result.getObject("character_maximum_length"),
                        result.getString("is_nullable"),
                        result.getString("column_default")
                );
            }
        }
    }

    private static void assertNumericCoordinate(ColumnInfo column) {
        assertAll(
                () -> assertEquals("numeric", column.dataType()),
                () -> assertEquals(10, column.precision()),
                () -> assertEquals(7, column.scale()),
                () -> assertEquals("YES", column.nullable())
        );
    }

    private static List<String> values(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record ColumnInfo(
            String dataType,
            Integer precision,
            Integer scale,
            Integer maximumLength,
            String nullable,
            String defaultValue
    ) {
        private ColumnInfo {
            assertNotNull(dataType);
        }
    }
}
