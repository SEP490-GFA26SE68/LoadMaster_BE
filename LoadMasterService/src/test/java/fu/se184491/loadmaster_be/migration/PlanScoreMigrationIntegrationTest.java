package fu.se184491.loadmaster_be.migration;

import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlanScoreComponentResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlanScoreResponse;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V57 (điểm J và lý do đặt): chỉ thêm cột cho phép trống, đúng kiểu mà entity khai (ddl-auto: validate); dòng cũ để trống;
 * chạy lại không lỗi; giá trị dịch vụ tối ưu ghi (jsonb, numeric) đọc lại được qua LoadPlanReadRepository.
 */
class PlanScoreMigrationIntegrationTest {

    private static final String MIGRATION = "db/migration/V57__luu_diem_j_ly_do_dat.sql";

    @Test
    void migrationAddsNullableColumnsThatTheReadRepositoryReturns() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            try (Connection connection = connection(postgres)) {
                // Đủ cột mà hai câu đọc của LoadPlanReadRepository dùng, ở dạng trước V57.
                execute(connection, """
                        CREATE TABLE users (id BIGINT PRIMARY KEY, full_name VARCHAR(150));
                        CREATE TABLE trips (id BIGINT PRIMARY KEY, company_id BIGINT);
                        CREATE TABLE optimization_jobs (id BIGINT PRIMARY KEY, job_uuid VARCHAR(64), trip_id BIGINT,
                            status VARCHAR(30), objective VARCHAR(30), algorithm_name VARCHAR(60), computation_ms BIGINT,
                            vehicle_id BIGINT);
                        CREATE TABLE load_plans (id BIGINT PRIMARY KEY, job_id BIGINT, plan_name VARCHAR(100),
                            version INT, parent_plan_id BIGINT, packed_items_count INT,
                            volume_utilization NUMERIC(7, 4), weight_utilization NUMERIC(7, 4), is_approved BOOLEAN,
                            approved_by BIGINT, cog_x NUMERIC(10, 3), cog_y NUMERIC(10, 3), cog_z NUMERIC(10, 3),
                            front_axle_load NUMERIC(10, 2), rear_axle_load NUMERIC(10, 2), rehandling_count INT,
                            objective VARCHAR(30), candidate_label VARCHAR(1), algorithm_name VARCHAR(60),
                            manually_edited BOOLEAN, is_stale BOOLEAN);
                        CREATE TABLE package_types (id BIGINT PRIMARY KEY, rotation_allowed BOOLEAN,
                            allow_rotate_x BOOLEAN, allow_rotate_y BOOLEAN, allow_rotate_z BOOLEAN);
                        CREATE TABLE packages (id BIGINT PRIMARY KEY, package_code VARCHAR(50),
                            tracking_barcode VARCHAR(100), actual_weight_kg NUMERIC(10, 2), package_type_id BIGINT);
                        CREATE TABLE cargo_package_packing_profiles (package_id BIGINT PRIMARY KEY, upright_only BOOLEAN);
                        CREATE TABLE delivery_stops (id BIGINT PRIMARY KEY, stop_name VARCHAR(150), stop_sequence INT);
                        CREATE TABLE package_placements (id BIGINT PRIMARY KEY, load_plan_id BIGINT, package_id BIGINT,
                            pos_x NUMERIC(10, 2), pos_y NUMERIC(10, 2), pos_z NUMERIC(10, 2),
                            packed_length NUMERIC(10, 2), packed_width NUMERIC(10, 2), packed_height NUMERIC(10, 2),
                            rotation_type INT, step_sequence INT, pinned BOOLEAN, stop_zone_id BIGINT);
                        INSERT INTO trips VALUES (10, 1);
                        INSERT INTO optimization_jobs VALUES (100, 'job-100', 10, 'COMPLETED', 'MAX_VOLUME', 'EP_DBLF', 900, 3);
                        INSERT INTO packages VALUES (1, 'PK-1', 'BC-1', 15, NULL), (2, 'PK-2', 'BC-2', 20, NULL);
                        INSERT INTO delivery_stops VALUES (70, 'Kho Biên Hoà', 1);
                        INSERT INTO load_plans (id, job_id, version, is_approved) VALUES (1, 100, 1, false);
                        INSERT INTO package_placements VALUES (11, 1, 1, 0, 0, 0, 50, 40, 30, 0, 1, false, 70);
                        """);

                execute(connection, script(MIGRATION));
                execute(connection, script(MIGRATION));  // chạy lại: không lỗi, không đổi gì

                assertThat(columns(connection, "load_plans")).containsEntry("plan_score", "jsonb")
                        .containsEntry("candidate_profile", "character varying(20)")
                        .containsEntry("duplicate_of", "character varying(1)");
                assertThat(columns(connection, "package_placements")).containsEntry("reason_codes", "jsonb")
                        .containsEntry("stack_pressure_kg_m2", "numeric(16,3)")
                        .containsEntry("top_load_capacity_kg_m2", "numeric(16,3)")
                        .containsEntry("support_ratio", "numeric(5,4)")
                        .containsEntry("distance_to_door_cm", "numeric(10,3)");

                // Như dịch vụ tối ưu ghi khi cờ PLAN_SCORE_J bật.
                execute(connection, """
                        INSERT INTO load_plans (id, job_id, version, is_approved, candidate_label, plan_score,
                            candidate_profile, duplicate_of)
                        VALUES (2, 100, 1, false, 'B', '{"profile": "B", "profile_name": "Cân trục",
                            "assumed_weights": true, "j": 61.25, "u_vol": 0.42, "u_n": 1.0, "w_vol": 1.0, "w_n": 0.5,
                            "components": [{"code": "S1", "name": "Lệch trọng tâm", "value": 0.1, "weight": 0.3,
                                            "contribution": -3.0, "computed": true}]}'::jsonb, 'B', 'A');
                        INSERT INTO package_placements (id, load_plan_id, package_id, pos_x, pos_y, pos_z,
                            packed_length, packed_width, packed_height, rotation_type, step_sequence, pinned,
                            stop_zone_id, reason_codes, stack_pressure_kg_m2, top_load_capacity_kg_m2, support_ratio,
                            distance_to_door_cm)
                        VALUES (21, 2, 2, 0, 0, 0, 50, 40, 30, 0, 1, false, 70, '["ZONE_STOP_1", "HEAVY_LOW"]'::jsonb,
                            0, 5000, 1, 350.5);
                        """);
            }

            LoadPlanReadRepository repository = new LoadPlanReadRepository(new NamedParameterJdbcTemplate(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())));
            List<LoadPlanDetailResponse> plans = repository.findPlansByJob(100L);

            LoadPlanDetailResponse old = plans.get(0);
            assertThat(old.getPlanScore()).isNull();
            assertThat(old.getCandidateProfile()).isNull();
            assertThat(old.getDuplicateOf()).isNull();
            LoadPlanDetailResponse scored = plans.get(1);
            assertThat(scored.getCandidateProfile()).isEqualTo("B");
            assertThat(scored.getDuplicateOf()).isEqualTo("A");
            assertThat(scored.getPlanScore()).isEqualTo(new PlanScoreResponse("B", "Cân trục", true, 61.25, 0.42, 1.0,
                    1.0, 0.5, List.of(new PlanScoreComponentResponse("S1", "Lệch trọng tâm", 0.1, 0.3, -3.0, true))));

            List<LoadPlanReadRepository.PlacementRow> rows = repository.findPlacements(List.of(1L, 2L));
            PlacementDetailResponse oldPlacement = rows.get(0).placement();
            assertThat(oldPlacement.getReasonCodes()).isNull();
            assertThat(oldPlacement.getStackPressureKgM2()).isNull();
            assertThat(oldPlacement.getTopLoadCapacityKgM2()).isNull();
            assertThat(oldPlacement.getSupportRatio()).isNull();
            assertThat(oldPlacement.getDistanceToDoorCm()).isNull();
            PlacementDetailResponse placed = rows.get(1).placement();
            assertThat(placed.getReasonCodes()).containsExactly("ZONE_STOP_1", "HEAVY_LOW");
            assertThat(placed.getStackPressureKgM2()).isEqualByComparingTo("0");
            assertThat(placed.getTopLoadCapacityKgM2()).isEqualByComparingTo("5000");
            assertThat(placed.getSupportRatio()).isEqualByComparingTo("1");
            assertThat(placed.getDistanceToDoorCm()).isEqualByComparingTo("350.5");
        }
    }

    /** Tên cột → kiểu cột như PostgreSQL in ra (format_type). */
    private static Map<String, String> columns(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull
                     FROM pg_attribute a
                     WHERE a.attrelid = '%s'::regclass AND a.attnum > 0 AND NOT a.attisdropped
                     """.formatted(table))) {
            Map<String, String> types = new HashMap<>();
            while (result.next()) {
                // Cột mới phải cho phép trống: dòng cũ và bản chỉnh tay không có giá trị.
                String type = result.getBoolean(3) ? result.getString(2) + " not null" : result.getString(2);
                types.put(result.getString(1), type);
            }
            return types;
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
        try (InputStream input = PlanScoreMigrationIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
