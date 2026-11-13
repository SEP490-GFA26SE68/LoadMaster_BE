package fu.se184491.loadmaster_be.repository.optimization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trạng thái cuối của job tối ưu là cuối, ở mức câu SQL: lượt đánh dấu JOB_INTERRUPTED và lượt lưu kết quả đều là
 * UPDATE có điều kiện trên PENDING / RUNNING, nên chỉ một lượt thắng và lượt kia không ghi đè.
 */
class OptimizationJobFinalStatusTest {

    private NamedParameterJdbcTemplate jdbc;
    private LoadPlanWriteRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:job-final-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE optimization_jobs (
                    id BIGINT PRIMARY KEY, job_uuid VARCHAR(64) NOT NULL, status VARCHAR(50) NOT NULL,
                    computation_ms BIGINT, failure_code VARCHAR(64))
                """);
        jdbc.update("INSERT INTO optimization_jobs (id, job_uuid, status) VALUES (1, 'job-1', 'RUNNING')", Map.of());
        repository = new LoadPlanWriteRepository(jdbc);
    }

    private Map<String, Object> job() {
        return jdbc.queryForMap("SELECT status, computation_ms, failure_code FROM optimization_jobs WHERE id = 1",
                Map.of());
    }

    @Test
    void resultArrivingAfterInterruptionDoesNotOverwriteTheFailure() {
        assertThat(repository.failUnfinishedJob(1L, "JOB_INTERRUPTED")).isEqualTo(1);

        assertThat(repository.finishUnfinishedJob("job-1", "COMPLETED", 900L)).isZero();

        assertThat(job()).containsEntry("status", "FAILED")
                .containsEntry("failure_code", "JOB_INTERRUPTED")
                .containsEntry("computation_ms", null);
    }

    @Test
    void interruptionAfterTheResultWasSavedChangesNothing() {
        assertThat(repository.finishUnfinishedJob("job-1", "COMPLETED", 900L)).isEqualTo(1);

        // Không đổi được dòng nào nên InterruptedOptimizationJobService không hoàn credit
        assertThat(repository.failUnfinishedJob(1L, "JOB_INTERRUPTED")).isZero();

        assertThat(job()).containsEntry("status", "COMPLETED")
                .containsEntry("failure_code", null)
                .containsEntry("computation_ms", 900L);
    }

    @Test
    void failingAnInterruptedJobKeepsItsReason() {
        repository.failUnfinishedJob(1L, "JOB_INTERRUPTED");

        assertThat(repository.finishUnfinishedJob("job-1", "FAILED", null)).isZero();

        assertThat(job()).containsEntry("failure_code", "JOB_INTERRUPTED");
    }
}
