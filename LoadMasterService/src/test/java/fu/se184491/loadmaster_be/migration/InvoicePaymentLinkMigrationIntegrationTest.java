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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V38: invoices có cột liên kết thanh toán (duy nhất), gói, loại, số credit; chạy lại không lỗi, giữ hoá đơn cũ. */
class InvoicePaymentLinkMigrationIntegrationTest {

    private static final String SCRIPT = "db/migration/V38__invoices_payment_link.sql";

    @Test
    void migrationLinksInvoicesToPaymentsAndIsIdempotent() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the PostgreSQL migration integration test");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (Connection connection = connection(postgres)) {
                // Bảng do ddl-auto tạo trước bản sửa: hoá đơn hợp đồng cũ không có thanh toán
                execute(connection, "CREATE SCHEMA billing");
                execute(connection, "CREATE TABLE billing.payment_transactions (id BIGSERIAL PRIMARY KEY)");
                execute(connection, "CREATE TABLE billing.subscription_plans (id BIGSERIAL PRIMARY KEY)");
                execute(connection, "CREATE TABLE billing.invoices (id BIGSERIAL PRIMARY KEY, invoice_number VARCHAR(255))");
                execute(connection, "INSERT INTO billing.invoices (invoice_number) VALUES ('INV-1-1700000000000')");
                execute(connection, "INSERT INTO billing.payment_transactions DEFAULT VALUES");
            }

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas("billing")
                    .baselineOnMigrate(true)
                    .baselineVersion(MigrationVersion.fromVersion("37"))
                    .target(MigrationVersion.fromVersion("38"))
                    .load()
                    .migrate();

            try (Connection connection = connection(postgres)) {
                assertEquals("1", scalar(connection, "SELECT count(*) FROM billing.invoices"));
                execute(connection, "INSERT INTO billing.invoices (invoice_number, payment_transaction_id, item_type,"
                        + " credit_quantity) VALUES ('INV-1-P1', 1, 'CREDIT_TOPUP', 50)");
                // Một thanh toán chỉ một hoá đơn; thanh toán không tồn tại bị từ chối
                assertThrows(SQLException.class, () -> execute(connection,
                        "INSERT INTO billing.invoices (invoice_number, payment_transaction_id) VALUES ('INV-1-P1b', 1)"));
                assertThrows(SQLException.class, () -> execute(connection,
                        "INSERT INTO billing.invoices (invoice_number, payment_transaction_id) VALUES ('INV-1-P9', 9)"));

                // Chạy lại nguyên script: không lỗi, không thêm ràng buộc thứ hai
                execute(connection, "SET search_path TO billing");
                execute(connection, script());
                execute(connection, "SET search_path TO public");
                assertEquals("3", scalar(connection, "SELECT count(*) FROM pg_constraint WHERE conname IN"
                        + " ('uk_invoices_payment_transaction', 'fk_invoices_payment_transaction', 'fk_invoices_plan')"));
            }
        }
    }

    private static String script() throws IOException {
        try (InputStream in = InvoicePaymentLinkMigrationIntegrationTest.class.getClassLoader()
                .getResourceAsStream(SCRIPT)) {
            assertNotNull(in, SCRIPT);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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
