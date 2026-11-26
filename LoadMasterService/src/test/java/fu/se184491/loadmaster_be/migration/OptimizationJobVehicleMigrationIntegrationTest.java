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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V56 (xe của lần chạy tối ưu): thêm cột, điền xe hiện tại của chuyến cho job còn phương án chưa lỗi thời, để trống job chỉ có
 * phương án lỗi thời (đã đổi xe sau đó, không biết chắc xe nào); chạy lại không đổi thêm gì; file xem trước chạy được cả trước
 * lẫn sau V56.
 */
class OptimizationJobVehicleMigrationIntegrationTest {

    private static final String MIGRATION = "db/migration/V56__optimization_job_vehicle.sql";
    private static final String PREVIEW = "db/manual/2026-11-optimization-job-vehicle-preview.sql";

    @Test
    void migrationFillsTheTripVehicleOnlyForJobsWhosePlansAreStillCurrent() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (Connection connection = connection(postgres)) {
                execute(connection, """
                        CREATE TABLE vehicles (id BIGINT PRIMARY KEY);
                        CREATE TABLE trips (id BIGINT PRIMARY KEY, vehicle_id BIGINT REFERENCES vehicles (id));
                        CREATE TABLE optimization_jobs (id BIGINT PRIMARY KEY, trip_id BIGINT REFERENCES trips (id));
                        CREATE TABLE load_plans (id BIGINT PRIMARY KEY, job_id BIGINT REFERENCES optimization_jobs (id),
                            is_stale BOOLEAN);
                        INSERT INTO vehicles VALUES (1), (2);
                        INSERT INTO trips VALUES (10, 2), (11, 1);
                        INSERT INTO optimization_jobs VALUES (100, 10), (101, 10), (102, 11), (103, 11);
                        INSERT INTO load_plans VALUES
                            (1, 100, true), (2, 100, true),
                            (3, 101, false), (4, 101, NULL),
                            (5, 102, NULL);
                        """);
                execute(connection, script(PREVIEW));  // trước V56: chỉ đọc, không lỗi

                execute(connection, script(MIGRATION));

                assertNull(vehicleOf(connection, 100), "chỉ có phương án lỗi thời: không biết xe nào");
                assertEquals("2", vehicleOf(connection, 101));
                assertEquals("1", vehicleOf(connection, 102));
                assertNull(vehicleOf(connection, 103), "không có phương án nào");

                execute(connection, "UPDATE optimization_jobs SET vehicle_id = 1 WHERE id = 101");
                execute(connection, script(MIGRATION));  // chạy lại: không ghi đè xe đã có
                assertEquals("1", vehicleOf(connection, 101));
                execute(connection, script(PREVIEW));  // sau V56: vẫn chạy được
            }
        }
    }

    private static String vehicleOf(Connection connection, long jobId) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT vehicle_id FROM optimization_jobs WHERE id = " + jobId)) {
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
        try (InputStream input = OptimizationJobVehicleMigrationIntegrationTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
