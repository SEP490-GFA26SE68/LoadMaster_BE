package fu.se184491.loadmaster_be.entity.account;

import fu.se184491.loadmaster_be.support.TestSqlFixtures;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Characterizes existing migration blockers; never loads shared datasource settings or application jobs. */
class UserTimestampMigrationAuditTest {
    @TempDir
    Path migrations;

    @Test
    void freshDatabaseIsBlockedByHistoryAndMissingBaseline() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            var duplicate = assertThrows(FlywayException.class, () -> Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").load().migrate());
            assertTrue(duplicate.getMessage().contains("more than one migration with version"), duplicate.getMessage());

            var names = assertThrows(FlywayException.class, () -> Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").validateMigrationNaming(true).load().migrate());
            // Malformed filenames were renamed; duplicate versions still block full history.
            assertTrue(names.getMessage().contains("more than one migration with version"), names.getMessage());
            for (String name : new String[]{"V10__add_trip_origin_fields.sql", "V11__add_trip_delivery_stops.sql", "V12__add_columns_delivery_requirement.sql"}) {
                assertNotNull(getClass().getResource("/db/migration/" + name));
            }

            // Copy only unmodified V1/V2 into a test-only location to expose the next blocker behind duplicate versions.
            for (String name : new String[]{"V1__trip_status_stop_coordinates.sql", "V2__delivery_requirement_crud.sql"}) {
                Files.writeString(migrations.resolve(name), resource(name));
            }
            var baseline = assertThrows(FlywayException.class, () -> Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("filesystem:" + migrations.toAbsolutePath()).load().migrate());
            assertTrue(baseline.getMessage().contains("packages"), baseline.getMessage());
            try (var connection = connection(postgres)) {
                assertNull(scalar(connection, "SELECT to_regclass('public.users')::text"));
            }
        }
    }

    @Test
    void unchangedV19CreatesLocalStringsAndLosesFractionalSeconds() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (var connection = connection(postgres); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE users (created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ)");
                statement.execute("INSERT INTO users VALUES ('2026-11-04T15:00:00.123456Z', '2026-11-04T15:00:01.654321Z')");
                connection.setAutoCommit(false);
                statement.execute("SET LOCAL TIME ZONE 'UTC'");
                statement.execute(resource("V19__format_users_created_updated_at.sql"));
                assertEquals("character varying", scalar(connection, """
                        SELECT data_type FROM information_schema.columns
                        WHERE table_schema='public' AND table_name='users' AND column_name='created_at'
                        """));
                assertEquals("2026-11-04 22:00:00", scalar(connection, "SELECT created_at FROM users"));
                assertEquals("2026-11-04 22:00:01", scalar(connection, "SELECT updated_at FROM users"));
                // An explicitly known source zone can recover whole seconds, but not fractions already removed by V19.
                assertEquals("2026-11-04 15:00:00+00", scalar(connection,
                        "SELECT (created_at::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::text FROM users"));
                connection.rollback();
                assertEquals(Instant.parse("2026-11-04T15:00:00.123456Z"), createdAt(connection));
                connection.rollback();
            }
        }
    }

    @Test
    void proposedConstraintsRejectInvalidRowsAndDoNotRewriteOrPersistWithoutApproval() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        String proposal = TestSqlFixtures.read("proposed-users-timestamp-constraints.sql")
                .replaceFirst("(?m)^BEGIN;\\s*$", "")
                .replaceFirst("(?m)^ROLLBACK;\\s*$", "");
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (var connection = connection(postgres); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE users (created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ)");
                statement.execute("INSERT INTO users VALUES ('2026-11-04T15:00:00.123456Z','2026-11-04T15:00:01.654321Z')");
                connection.setAutoCommit(false);
                try {
                    statement.execute(proposal);
                    assertEquals("NO", updatedNullable(connection));
                    assertEquals("1", orderingChecks(connection));
                    rejectInsert(connection, "INSERT INTO users VALUES (CURRENT_TIMESTAMP,NULL)", "23502");
                    rejectInsert(connection, "INSERT INTO users VALUES ('2026-11-04T15:00:01Z','2026-11-04T15:00:00Z')", "23514");
                    assertEquals(Instant.parse("2026-11-04T15:00:00.123456Z"), createdAt(connection));
                } finally {
                    connection.rollback();
                }
                assertEquals("YES", updatedNullable(connection));
                assertEquals("0", orderingChecks(connection));
                connection.commit();
                connection.setAutoCommit(true);
                statement.execute("INSERT INTO users VALUES ('2026-11-04T15:00:00Z',NULL),('2026-11-04T15:00:01Z','2026-11-04T15:00:00Z')");
                connection.setAutoCommit(false);
                try {
                    var invalid = assertThrows(SQLException.class, () -> statement.execute(proposal));
                    assertEquals("P0001", invalid.getSQLState());
                } finally {
                    connection.rollback();
                }
                assertEquals("3", scalar(connection, "SELECT count(*)::text FROM users"));
                assertEquals("2", scalar(connection, "SELECT count(*)::text FROM users WHERE updated_at IS NULL OR updated_at < created_at"));
                assertEquals("YES", updatedNullable(connection));
                assertEquals("0", orderingChecks(connection));
                connection.rollback();
            }
        }
    }

    private static void rejectInsert(Connection connection, String sql, String state) throws Exception {
        var savepoint = connection.setSavepoint();
        try (var statement = connection.createStatement()) {
            var exception = assertThrows(SQLException.class, () -> statement.execute(sql));
            assertEquals(state, exception.getSQLState());
        } finally {
            connection.rollback(savepoint);
        }
    }

    private static String updatedNullable(Connection connection) throws SQLException {
        return scalar(connection, "SELECT is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name='updated_at'");
    }

    private static String orderingChecks(Connection connection) throws SQLException {
        return scalar(connection, "SELECT count(*)::text FROM pg_constraint WHERE conrelid='public.users'::regclass AND conname='chk_users_updated_after_created'");
    }

    private static Connection connection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static Instant createdAt(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT created_at FROM users")) {
            assertTrue(rows.next());
            return rows.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    private static String resource(String filename) throws Exception {
        try (var stream = UserTimestampMigrationAuditTest.class.getResourceAsStream("/db/migration/" + filename)) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
