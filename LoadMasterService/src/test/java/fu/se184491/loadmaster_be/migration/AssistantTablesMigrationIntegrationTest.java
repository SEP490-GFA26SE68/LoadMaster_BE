package fu.se184491.loadmaster_be.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** V53: ba bảng của trợ lý AI có đủ cột, kiểu, ràng buộc; chạy lại không lỗi. */
class AssistantTablesMigrationIntegrationTest {

    private static final String SCRIPT = "db/migration/V53__assistant_tables.sql";
    private static final String SCHEMA = "app";

    @Test
    void migrationCreatesTheThreeTablesWithTheirConstraints() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the PostgreSQL migration integration test");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (Connection connection = connection(postgres)) {
                // Schema đã có bảng khác (như DB đang chạy) để Flyway baseline ở V52 rồi chỉ chạy V53
                execute(connection, "CREATE SCHEMA " + SCHEMA);
                execute(connection, "CREATE TABLE " + SCHEMA + ".users (id BIGSERIAL PRIMARY KEY)");
            }

            migrate(postgres);

            try (Connection connection = connection(postgres)) {
                assertEquals("timestamp with time zone", type(connection, "assistant_conversations", "created_at"));
                assertEquals("timestamp with time zone", type(connection, "assistant_messages", "created_at"));
                assertEquals("jsonb", type(connection, "assistant_messages", "ui_actions"));
                assertEquals("jsonb", type(connection, "assistant_tool_calls", "arguments"));
                assertEquals("text", type(connection, "assistant_messages", "content"));

                execute(connection, "SET search_path TO " + SCHEMA);
                execute(connection, "INSERT INTO assistant_conversations (public_id, channel, created_at, updated_at)"
                        + " VALUES ('conv-1', 'PUBLIC', now(), now())");
                assertThrows(SQLException.class, () -> execute(connection,
                        "INSERT INTO assistant_conversations (public_id, channel, created_at, updated_at)"
                                + " VALUES ('conv-1', 'APP', now(), now())"));

                String messageId = scalar(connection, "INSERT INTO assistant_messages (conversation_id, role, content,"
                        + " ui_actions, created_at) SELECT id, 'ASSISTANT', 'Xin chào', '[{\"type\":\"select_package\"}]',"
                        + " now() FROM assistant_conversations WHERE public_id = 'conv-1' RETURNING id::text");
                assertEquals("false,0,0", scalar(connection, "SELECT handoff_offered::text || ',' || input_tokens"
                        + " || ',' || output_tokens FROM assistant_messages WHERE id = " + messageId));
                assertThrows(SQLException.class, () -> execute(connection, "INSERT INTO assistant_messages"
                        + " (conversation_id, role, content, created_at) VALUES (999999, 'USER', 'x', now())"));

                execute(connection, "INSERT INTO assistant_tool_calls (message_id, tool_name, arguments, ok,"
                        + " duration_ms, created_at) VALUES (" + messageId + ", 'get_trip', '{\"tripCode\":\"TRIP-1\"}',"
                        + " true, 12, now())");
                assertThrows(SQLException.class, () -> execute(connection, "INSERT INTO assistant_tool_calls"
                        + " (message_id, tool_name, ok, duration_ms, created_at) VALUES (999999, 'x', true, 1, now())"));

                // Không khoá ngoại tới users / companies
                assertEquals("0", scalar(connection, "SELECT count(*) FROM information_schema.table_constraints tc"
                        + " JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name"
                        + " WHERE tc.table_schema = '" + SCHEMA + "' AND tc.constraint_type = 'FOREIGN KEY'"
                        + " AND ccu.table_name IN ('users', 'companies')"));

                assertEquals("4", scalar(connection, "SELECT count(*) FROM pg_indexes WHERE schemaname = '" + SCHEMA
                        + "' AND indexname IN ('idx_assistant_conversations_user_id',"
                        + " 'idx_assistant_messages_conversation_id', 'idx_assistant_messages_created_at',"
                        + " 'idx_assistant_tool_calls_message_id')"));

                // Chạy lại nguyên script: không lỗi, dữ liệu còn nguyên
                execute(connection, script());
                assertEquals("1", scalar(connection, "SELECT count(*) FROM assistant_messages"));
            }
        }
    }

    private static void migrate(PostgreSQLContainer postgres) throws IOException {
        // Chỉ dò thư mục chứa đúng V53: db/migration của develop có hai file V47 nên Flyway dò cả thư mục sẽ báo trùng
        Path dir = Files.createTempDirectory("assistant-migration");
        Files.writeString(dir.resolve("V53__assistant_tables.sql"), script());
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + dir.toAbsolutePath())
                .schemas(SCHEMA)
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("52"))
                .target(MigrationVersion.fromVersion("53"))
                .load()
                .migrate();
    }

    private static String script() throws IOException {
        try (InputStream in = AssistantTablesMigrationIntegrationTest.class.getClassLoader().getResourceAsStream(SCRIPT)) {
            assertNotNull(in, SCRIPT);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String type(Connection connection, String table, String column) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT data_type FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = ? AND column_name = ?")) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), () -> "Missing column " + table + "." + column);
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
