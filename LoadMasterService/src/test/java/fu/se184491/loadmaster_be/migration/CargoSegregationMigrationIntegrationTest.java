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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CargoSegregationMigrationIntegrationTest {

    @Test
    void migrationAddsHazardousCapabilityToVehicleTypes() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the PostgreSQL migration integration test");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            createPrerequisiteSchema(postgres);

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .baselineOnMigrate(true)
                    .baselineVersion(MigrationVersion.fromVersion("0"))
                    .load()
                    .migrate();

            try (Connection connection = connection(postgres)) {
                ColumnInfo column = column(connection, "vehicle_types", "hazardous_capable");
                assertAll(
                        () -> assertEquals("boolean", column.dataType()),
                        () -> assertEquals("NO", column.nullable()),
                        () -> assertTrue(column.defaultValue().contains("false")),
                        () -> assertFalse(insertAndReturnCapability(connection))
                );
            }
        }
    }

    private static void createPrerequisiteSchema(PostgreSQLContainer postgres) throws SQLException {
        try (Connection connection = connection(postgres); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE companies (id BIGSERIAL PRIMARY KEY)");
            statement.execute("CREATE TABLE users (id BIGSERIAL PRIMARY KEY)");
            statement.execute("CREATE TABLE orders (id BIGSERIAL PRIMARY KEY, company_id BIGINT REFERENCES companies(id))");
            statement.execute("CREATE TABLE packages (id BIGSERIAL PRIMARY KEY, order_id BIGINT REFERENCES orders(id))");
            statement.execute("CREATE TABLE vehicle_types (id BIGSERIAL PRIMARY KEY)");
        }
    }

    private static boolean insertAndReturnCapability(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "INSERT INTO vehicle_types DEFAULT VALUES RETURNING hazardous_capable")) {
            assertTrue(result.next());
            return result.getBoolean(1);
        }
    }

    private static Connection connection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static ColumnInfo column(Connection connection, String table, String column) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), () -> "Missing column " + table + "." + column);
                return new ColumnInfo(
                        result.getString("data_type"),
                        result.getString("is_nullable"),
                        result.getString("column_default")
                );
            }
        }
    }

    private record ColumnInfo(String dataType, String nullable, String defaultValue) {
    }
}
