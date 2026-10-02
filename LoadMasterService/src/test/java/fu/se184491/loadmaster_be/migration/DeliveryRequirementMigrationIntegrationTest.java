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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DeliveryRequirementMigrationIntegrationTest {

    @Test
    void migrationCreatesTenantScopedDeliveryRequirementSchema() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the PostgreSQL migration integration test");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            createPrerequisiteSchema(postgres);

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .baselineOnMigrate(true)
                    .baselineVersion(MigrationVersion.fromVersion("0"))
                    .target(MigrationVersion.fromVersion("2"))
                    .load()
                    .migrate();

            try (Connection connection = connection(postgres)) {
                assertAll(
                        () -> assertTrue(tableExists(connection, "delivery_requirements")),
                        () -> assertTrue(tableExists(connection, "delivery_requirement_packages")),
                        () -> assertEquals("NO", column(connection, "delivery_requirements", "company_id").nullable()),
                        () -> assertEquals("numeric", column(connection, "delivery_requirements", "destination_lat").dataType()),
                        () -> assertEquals(10, column(connection, "delivery_requirements", "destination_lat").precision()),
                        () -> assertEquals(7, column(connection, "delivery_requirements", "destination_lat").scale()),
                        () -> assertTrue(column(connection, "delivery_requirements", "priority").defaultValue().contains("NORMAL")),
                        () -> assertTrue(column(connection, "delivery_requirements", "status").defaultValue().contains("PENDING")),
                        () -> assertTrue(column(connection, "packages", "handling_class").defaultValue().contains("STANDARD")),
                        () -> assertThrows(SQLException.class,
                                () -> execute(connection, validInsert("UNKNOWN", "PENDING"))),
                        () -> assertThrows(SQLException.class,
                                () -> execute(connection, validInsert("NORMAL", "UNKNOWN")))
                );
            }
        }
    }

    private static String validInsert(String priority, String status) {
        return "INSERT INTO delivery_requirements(company_id, destination, deadline, priority, status) "
                + "VALUES (1, 'Da Nang', NOW() + INTERVAL '1 day', '" + priority + "', '" + status + "')";
    }

    private static void createPrerequisiteSchema(PostgreSQLContainer postgres) throws SQLException {
        try (Connection connection = connection(postgres); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE companies (id BIGSERIAL PRIMARY KEY)");
            statement.execute("CREATE TABLE users (id BIGSERIAL PRIMARY KEY)");
            statement.execute("CREATE TABLE orders (id BIGSERIAL PRIMARY KEY, company_id BIGINT REFERENCES companies(id))");
            statement.execute("CREATE TABLE packages (id BIGSERIAL PRIMARY KEY, order_id BIGINT REFERENCES orders(id))");
            statement.execute("INSERT INTO companies DEFAULT VALUES");
        }
    }

    private static Connection connection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = ?
                )
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private static ColumnInfo column(Connection connection, String table, String column) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT data_type, numeric_precision, numeric_scale, is_nullable, column_default
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
                        result.getString("is_nullable"),
                        result.getString("column_default")
                );
            }
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
            String nullable,
            String defaultValue
    ) {
    }
}
