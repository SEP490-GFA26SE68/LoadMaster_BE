package fu.se184491.loadmaster_be.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V37: packages.is_pinned và packages.qr_token khớp entity, chạy lại không lỗi, không tự đặt NOT NULL khi còn dòng thiếu mã. */
class PackageQrTokenPinnedMigrationIntegrationTest {

    private static final String SCRIPT = "db/migration/V37__packages_qr_token_and_pinned.sql";

    @Test
    void migrationAlignsLegacyPackagesAndIsIdempotent() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the PostgreSQL migration integration test");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (Connection connection = connection(postgres)) {
                // Bảng do ddl-auto tạo: có qr_token đủ mã nhưng không ràng buộc, is_pinned cho phép null
                execute(connection, "CREATE SCHEMA filled");
                execute(connection, "CREATE TABLE filled.packages (id BIGSERIAL PRIMARY KEY, qr_token VARCHAR(128),"
                        + " is_pinned BOOLEAN)");
                execute(connection, "INSERT INTO filled.packages (qr_token, is_pinned) VALUES ('a', NULL), ('b', TRUE)");
                // Bảng cũ chưa có hai cột, có một kiện chưa có mã
                execute(connection, "CREATE SCHEMA missing");
                execute(connection, "CREATE TABLE missing.packages (id BIGSERIAL PRIMARY KEY)");
                execute(connection, "INSERT INTO missing.packages DEFAULT VALUES");
            }

            migrate(postgres, "filled");
            migrate(postgres, "missing");

            try (Connection connection = connection(postgres)) {
                assertEquals("NO", nullable(connection, "filled", "is_pinned"));
                assertEquals("NO", nullable(connection, "filled", "qr_token"));
                assertEquals("false,true", scalar(connection,
                        "SELECT string_agg(is_pinned::text, ',' ORDER BY id) FROM filled.packages"));
                assertThrows(SQLException.class, () -> execute(connection,
                        "INSERT INTO filled.packages (qr_token) VALUES ('a')"));
                assertEquals("false", scalar(connection,
                        "INSERT INTO filled.packages (qr_token) VALUES ('c') RETURNING is_pinned::text"));

                assertEquals("NO", nullable(connection, "missing", "is_pinned"));
                assertEquals("YES", nullable(connection, "missing", "qr_token"));
                assertTrue(hasUniqueIndex(connection, "missing"));

                // Chạy lại nguyên script trên cả hai schema: không lỗi, không thêm chỉ mục thứ hai
                for (String schema : new String[]{"filled", "missing"}) {
                    execute(connection, "SET search_path TO " + schema);
                    execute(connection, script());
                    execute(connection, "SET search_path TO public");
                }
                assertEquals("1", scalar(connection, "SELECT count(*) FROM pg_indexes"
                        + " WHERE schemaname = 'missing' AND tablename = 'packages' AND indexdef LIKE '%qr_token%'"));

                // Có mã đủ rồi thì lần chạy sau đặt NOT NULL
                execute(connection, "UPDATE missing.packages SET qr_token = 'x'");
                execute(connection, "SET search_path TO missing");
                execute(connection, script());
                execute(connection, "SET search_path TO public");
                assertEquals("NO", nullable(connection, "missing", "qr_token"));
            }
        }
    }

    @Test
    void migrationSkipsDatabaseWithoutPackagesTable() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the PostgreSQL migration integration test");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (Connection connection = connection(postgres)) {
                execute(connection, "CREATE SCHEMA empty");
                execute(connection, "CREATE TABLE empty.other (id BIGSERIAL PRIMARY KEY)");
            }
            migrate(postgres, "empty");
            try (Connection connection = connection(postgres)) {
                assertFalse(Boolean.parseBoolean(scalar(connection,
                        "SELECT (to_regclass('empty.packages') IS NOT NULL)::text")));
            }
        }
    }

    private static void migrate(PostgreSQLContainer postgres, String schema) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("36"))
                .target(MigrationVersion.fromVersion("37"))
                .load()
                .migrate();
    }

    private static String script() throws IOException {
        try (InputStream in = PackageQrTokenPinnedMigrationIntegrationTest.class.getClassLoader()
                .getResourceAsStream(SCRIPT)) {
            assertNotNull(in, SCRIPT);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean hasUniqueIndex(Connection connection, String schema) throws SQLException {
        return Boolean.parseBoolean(scalar(connection, "SELECT (EXISTS (SELECT 1 FROM pg_index i"
                + " JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]"
                + " WHERE i.indrelid = '" + schema + ".packages'::regclass AND i.indisunique AND a.attname = 'qr_token'))::text"));
    }

    private static String nullable(Connection connection, String schema, String column) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'packages' AND column_name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), () -> "Missing column " + schema + ".packages." + column);
                return result.getString(1);
            }
        }
    }

    private static Connection connection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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
}
