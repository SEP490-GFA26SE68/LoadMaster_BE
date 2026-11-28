package fu.se184491.loadmaster_be.migration;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V59 (kiểu thân xe): thêm vehicle_types.body_style cho phép trống, đúng kiểu entity khai (ddl-auto: validate); dòng cũ để
 * trống; ràng buộc chỉ nhận tám kiểu; chạy lại không lỗi; file xem trước chạy được cả trước lẫn sau V59.
 */
class VehicleBodyStyleMigrationIntegrationTest {

    private static final String MIGRATION = "db/migration/V59__kieu_than_xe.sql";
    private static final String PREVIEW = "db/manual/2026-11-kieu-than-xe-preview.sql";

    @Test
    void migrationAddsANullableCheckedColumnAndCanRunTwice() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (Connection connection = connection(postgres)) {
                execute(connection, """
                        CREATE TABLE vehicle_types (id BIGINT PRIMARY KEY, company_id BIGINT NOT NULL,
                            name VARCHAR(150) NOT NULL);
                        INSERT INTO vehicle_types VALUES (1, 1, 'Thùng 6 m');
                        """);

                execute(connection, script(PREVIEW));      // trước V59: chỉ đọc, không lỗi
                execute(connection, script(MIGRATION));
                execute(connection, script(MIGRATION));    // chạy lại: không lỗi, không đổi gì
                execute(connection, script(PREVIEW));      // sau V59 cũng chạy được

                assertEquals("character varying(32)", columnType(connection));
                assertEquals(1, constraintCount(connection));
                assertNull(bodyStyleOf(connection, 1));   // dòng cũ để trống: web chọn theo kích thước

                execute(connection, "INSERT INTO vehicle_types VALUES (2, 1, 'Xe lạnh', 'TRUCK_REEFER')");
                execute(connection, "INSERT INTO vehicle_types VALUES (3, 1, 'Van', NULL)");
                assertEquals("TRUCK_REEFER", bodyStyleOf(connection, 2));
                assertThrows(SQLException.class,
                        () -> execute(connection, "INSERT INTO vehicle_types VALUES (4, 1, 'Xe buýt', 'BUS')"));
            }
        }
    }

    private static String columnType(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT format_type(a.atttypid, a.atttypmod), a.attnotnull
                     FROM pg_attribute a
                     WHERE a.attrelid = 'vehicle_types'::regclass AND a.attname = 'body_style' AND NOT a.attisdropped
                     """)) {
            assertEquals(true, result.next(), "body_style");
            // Cột mới phải cho phép trống
            assertEquals(false, result.getBoolean(2));
            return result.getString(1);
        }
    }

    private static int constraintCount(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT count(*) FROM pg_constraint WHERE conname = 'chk_vehicle_types_body_style'")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String bodyStyleOf(Connection connection, long id) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT body_style FROM vehicle_types WHERE id = " + id)) {
            result.next();
            return result.getString(1);
        }
    }

    private static Connection connection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String script(String resource) throws IOException {
        try (InputStream input = VehicleBodyStyleMigrationIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
