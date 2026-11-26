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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V55 (số liệu vật lý cho luật ràng buộc mới, spec 8.1–8.2): thêm cột, đổi tải chồng cũ sang σ [kg/m²] đúng công thức,
 * chạy lại không đổi thêm gì, không ghi đè σ đã nhập; file xem trước chạy được cả trước lẫn sau V55.
 */
class PhysicsDataMigrationIntegrationTest {

    private static final String MIGRATION = "db/migration/V55__rang_buoc_vat_ly_du_lieu.sql";
    private static final String PREVIEW = "db/manual/2026-11-rang-buoc-vat-ly-du-lieu-preview.sql";

    @Test
    void migrationConvertsLegacyStackLimitsToPressureOnceAndKeepsOldColumns() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (Connection connection = connection(postgres)) {
                execute(connection, """
                        CREATE TABLE package_types (id BIGINT PRIMARY KEY, company_id BIGINT, type_code VARCHAR(50),
                            name VARCHAR(150), length INTEGER, width INTEGER, max_stack_weight_kg NUMERIC(10, 2),
                            is_fragile BOOLEAN);
                        CREATE TABLE packages (id BIGINT PRIMARY KEY, package_type_id BIGINT, actual_length NUMERIC(10, 2),
                            actual_width NUMERIC(10, 2));
                        CREATE TABLE cargo_package_packing_profiles (id BIGINT PRIMARY KEY, package_id BIGINT,
                            stackable BOOLEAN, max_top_load_kg NUMERIC(10, 2), fragility_level INTEGER);
                        CREATE TABLE vehicle_types (id BIGINT PRIMARY KEY, max_cog_offset_ratio NUMERIC(5, 4));
                        CREATE TABLE optimization_jobs (id BIGINT PRIMARY KEY);
                        INSERT INTO package_types VALUES
                            (1, 1, 'BOX', 'Thùng 60x40', 60, 40, 120, false),
                            (2, 1, 'GLASS', 'Kính', 50, 50, 300, true),
                            (3, 1, 'BAG', 'Bao', 80, 50, NULL, false);
                        INSERT INTO packages VALUES (10, 1, NULL, NULL), (11, 3, 100, 25), (12, NULL, NULL, NULL), (13, 1, NULL, NULL);
                        INSERT INTO cargo_package_packing_profiles VALUES
                            (1, 10, false, 50, NULL),
                            (2, 11, true, 40, NULL),
                            (3, 12, true, 40, NULL),
                            (4, 13, NULL, NULL, 3);
                        INSERT INTO vehicle_types VALUES (1, 0.15);
                        INSERT INTO optimization_jobs VALUES (1);
                        """);
                execute(connection, script(PREVIEW));  // trước V55: chỉ đọc, không lỗi
            }

            try (Connection connection = connection(postgres)) {
                execute(connection, script(MIGRATION));
                execute(connection, "UPDATE package_types SET top_load_capacity_kg_m2 = 777 WHERE id = 3");  // người dùng nhập
                execute(connection, script(MIGRATION));  // chạy lại an toàn
                execute(connection, script(PREVIEW));

                // 120 kg / 0,24 m² = 500; dễ vỡ → 0; người dùng nhập giữ nguyên; cột cũ giữ nguyên.
                assertEquals("1:500.00:120.00,2:0.00:300.00,3:777.00:", scalar(connection, """
                        SELECT string_agg(id || ':' || coalesce(top_load_capacity_kg_m2::text, '') || ':'
                            || coalesce(max_stack_weight_kg::text, ''), ',' ORDER BY id) FROM package_types"""));
                // stackable = false → 0; 40 kg trên 100 × 25 cm (kích thước thật) = 160; kiện không có kích thước → trống;
                // fragility_level không đổi sang σ.
                assertEquals("1:0.00,2:160.00,3:,4:", scalar(connection, """
                        SELECT string_agg(id || ':' || coalesce(top_load_capacity_kg_m2::text, ''), ',' ORDER BY id)
                        FROM cargo_package_packing_profiles"""));
                assertEquals("[]", scalar(connection, "SELECT data_warnings::text FROM optimization_jobs WHERE id = 1"));
                assertNotNull(scalar(connection, "SELECT floor_load_capacity_kg_m2 IS NULL FROM vehicle_types"));

                assertThrows(SQLException.class, () -> execute(connection,
                        "UPDATE package_types SET min_support_ratio = 0.4 WHERE id = 1"));
            }
            try (Connection connection = connection(postgres)) {
                assertThrows(SQLException.class, () -> execute(connection,
                        "UPDATE vehicle_types SET max_cog_offset_ratio_y = 0.6 WHERE id = 1"));
            }
            try (Connection connection = connection(postgres)) {
                assertThrows(SQLException.class, () -> execute(connection,
                        "UPDATE cargo_package_packing_profiles SET top_load_capacity_kg_m2 = -1 WHERE id = 1"));
            }
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

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static String script(String resource) throws IOException {
        try (InputStream input = PhysicsDataMigrationIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
