package fu.se184491.loadmaster_be.migration;

import fu.se184491.loadmaster_be.repository.billing.CreditAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Các file sửa tay ở db/manual chạy đúng trên PostgreSQL thật (container dùng xong bỏ), chỉ đổi đúng các dòng cần
 * đổi, và chạy lại không đổi thêm gì. Cùng lúc kiểm câu INSERT ... ON CONFLICT của CreditAccountRepository.
 */
class ManualFixScriptsIntegrationTest {

    private static final String UTC_FIX = "db/manual/2026-11-utc-to-vietnam-time.sql";
    private static final String UTC_PREVIEW = "db/manual/2026-11-utc-to-vietnam-time-preview.sql";
    private static final String CREDIT_FIX = "db/manual/2026-11-create-missing-credit-accounts.sql";
    private static final String CREDIT_PREVIEW = "db/manual/2026-11-create-missing-credit-accounts-preview.sql";

    @Test
    void utcFixShiftsOnlyOldPythonRowsOnce() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (Connection connection = connection(postgres)) {
                execute(connection, """
                        CREATE TABLE optimization_jobs (id BIGINT PRIMARY KEY, job_uuid VARCHAR(64),
                            parent_plan_id BIGINT, created_at TIMESTAMP);
                        CREATE TABLE audit_logs (id BIGINT PRIMARY KEY, action_type VARCHAR(50), entity_id VARCHAR(64),
                            old_values TEXT, new_values TEXT, created_at TIMESTAMP);
                        INSERT INTO optimization_jobs VALUES
                            (1, 'py-old', NULL, '2026-11-10 03:00:00'),
                            (2, 'java-rerun', 5, '2026-11-10 10:00:00'),
                            (3, 'py-new', NULL, '2026-11-13 08:00:00');
                        INSERT INTO audit_logs VALUES
                            (1, 'PLAN_APPROVED', '7', NULL, '{"approved": true, "approved_by": 4}', '2026-11-10 03:00:00'),
                            (2, 'PLAN_APPROVED', '8', '{"approved": false}', '{"sourcePlanId": 1}', '2026-11-10 10:00:00'),
                            (3, 'TRIP_CREATED', '9', NULL, '{"approved_by": 4}', '2026-11-10 03:00:00'),
                            (4, 'PLAN_APPROVED', '10', NULL, '{"approved": true, "approved_by": 4}', '2026-11-13 08:00:00');
                        """);

                execute(connection, script(UTC_PREVIEW));
                assertEquals("false", scalar(connection, "SELECT (to_regclass('manual_fix_runs') IS NOT NULL)::text"));
            }

            // Chưa điền mốc thì dừng, không tạo gì
            try (Connection connection = connection(postgres)) {
                assertThrows(SQLException.class, () -> execute(connection, script(UTC_FIX)));
            }
            try (Connection connection = connection(postgres)) {
                assertEquals("false", scalar(connection, "SELECT (to_regclass('manual_fix_runs') IS NOT NULL)::text"));
            }

            String filled = script(UTC_FIX).replace("deployed_vn TIMESTAMP := NULL;",
                    "deployed_vn TIMESTAMP := TIMESTAMP '2026-11-12 23:59:59';");
            try (Connection connection = connection(postgres)) {
                execute(connection, filled);
                execute(connection, filled);

                assertEquals("2026-11-10 10:00:00,2026-11-10 10:00:00,2026-11-13 08:00:00", scalar(connection,
                        "SELECT string_agg(created_at::text, ',' ORDER BY id) FROM optimization_jobs"));
                assertEquals("2026-11-10 10:00:00,2026-11-10 10:00:00,2026-11-10 03:00:00,2026-11-13 08:00:00",
                        scalar(connection, "SELECT string_agg(created_at::text, ',' ORDER BY id) FROM audit_logs"));
                assertEquals("2", scalar(connection, "SELECT count(*)::text FROM manual_fix_utc_rows"));
                assertEquals("1", scalar(connection, "SELECT count(*)::text FROM manual_fix_runs"));
            }
        }
    }

    @Test
    void creditFixCreatesMissingAccountsOnceAndRepositoryInsertIsIdempotent() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (Connection connection = connection(postgres)) {
                // Cùng định nghĩa với V4 cho các bảng liên quan
                execute(connection, """
                        CREATE TABLE companies (id BIGINT PRIMARY KEY, company_name VARCHAR(255));
                        CREATE TABLE subscription_plans (id BIGINT PRIMARY KEY, name VARCHAR(100), monthly_credits INT);
                        CREATE TABLE company_subscriptions (id BIGSERIAL PRIMARY KEY,
                            company_id BIGINT NOT NULL UNIQUE REFERENCES companies(id) ON DELETE CASCADE,
                            plan_id BIGINT NOT NULL REFERENCES subscription_plans(id),
                            status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', started_at TIMESTAMP, expires_at TIMESTAMP);
                        CREATE TABLE credit_accounts (id BIGSERIAL PRIMARY KEY,
                            company_id BIGINT NOT NULL UNIQUE REFERENCES companies(id) ON DELETE CASCADE,
                            balance INT NOT NULL DEFAULT 0 CHECK (balance >= 0), version BIGINT DEFAULT 0);
                        INSERT INTO companies VALUES (1, 'Không giới hạn'), (2, 'Có sẵn'), (3, 'Hết hạn'), (4, 'Chưa gói');
                        INSERT INTO subscription_plans VALUES (1, 'Ultimate', NULL), (2, 'Pro', 10000);
                        INSERT INTO company_subscriptions (company_id, plan_id, status) VALUES
                            (1, 1, 'ACTIVE'), (2, 2, 'ACTIVE'), (3, 2, 'EXPIRED');
                        INSERT INTO credit_accounts (company_id, balance) VALUES (2, 50);
                        """);

                execute(connection, script(CREDIT_PREVIEW));
                assertEquals("1", scalar(connection, "SELECT count(*)::text FROM credit_accounts"));

                execute(connection, script(CREDIT_FIX));
                execute(connection, script(CREDIT_FIX));
                assertEquals("1:0,2:50", scalar(connection,
                        "SELECT string_agg(company_id || ':' || balance, ',' ORDER BY company_id) FROM credit_accounts"));

                String insert = CreditAccountRepository.class.getMethod("insertIfMissing", Long.class)
                        .getAnnotation(Query.class).value().replace(":companyId", "?");
                assertEquals(1, insertFor(connection, insert, 4L));
                assertEquals(0, insertFor(connection, insert, 4L));
                assertEquals(0, insertFor(connection, insert, 2L));
                assertEquals("1:0,2:50,4:0", scalar(connection,
                        "SELECT string_agg(company_id || ':' || balance, ',' ORDER BY company_id) FROM credit_accounts"));
            }
        }
    }

    private static int insertFor(Connection connection, String sql, long companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, companyId);
            return statement.executeUpdate();
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
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static String script(String path) throws IOException {
        try (InputStream in = ManualFixScriptsIntegrationTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
