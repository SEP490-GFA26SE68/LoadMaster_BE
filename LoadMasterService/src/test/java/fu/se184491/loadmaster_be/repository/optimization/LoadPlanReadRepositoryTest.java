package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStatusResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoadPlanReadRepositoryTest {

    @Mock NamedParameterJdbcTemplate jdbc;

    @InjectMocks LoadPlanReadRepository repository;

    /** Chạy RowMapper mà repository truyền cho jdbc trên các dòng giả. */
    @SuppressWarnings("unchecked")
    private void rows(ResultSet... resultSets) {
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(2);
            List<Object> mapped = new java.util.ArrayList<>();
            for (int i = 0; i < resultSets.length; i++) {
                mapped.add(mapper.mapRow(resultSets[i], i));
            }
            return mapped;
        });
    }

    private static ResultSet planRow(long id, String planObjective, String label, String jobObjective)
            throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(id);
        when(rs.getString("plan_objective")).thenReturn(planObjective);
        when(rs.getString("candidate_label")).thenReturn(label);
        when(rs.getString("objective")).thenReturn(jobObjective);
        return rs;
    }

    @Test
    void plansOfAJobCarryTheirOwnObjectiveAndLabel() throws SQLException {
        rows(planRow(1, "MAX_VOLUME", "A", "MAX_VOLUME"),
                planRow(2, "AXLE_BALANCE", "B", "MAX_VOLUME"),
                planRow(3, "MIN_REHANDLING", "C", "MAX_VOLUME"));

        List<LoadPlanDetailResponse> plans = repository.findPlansByJob(7L);

        assertThat(plans).extracting(LoadPlanDetailResponse::getCandidateLabel).containsExactly("A", "B", "C");
        assertThat(plans).extracting(LoadPlanDetailResponse::getObjective)
                .containsExactly("MAX_VOLUME", "AXLE_BALANCE", "MIN_REHANDLING");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), eq(Map.of("jobId", 7L)), any(RowMapper.class));
        assertThat(sql.getValue()).contains("lp.objective AS plan_objective", "lp.candidate_label", "ORDER BY lp.id");
    }

    @Test
    void oldPlanWithoutObjectiveFallsBackToTheJobObjective() throws SQLException {
        rows(planRow(1, null, null, "AXLE_BALANCE"));

        LoadPlanDetailResponse plan = repository.findPlansByTrip(10L).get(0);

        assertThat(plan.getObjective()).isEqualTo("AXLE_BALANCE");
        assertThat(plan.getCandidateLabel()).isNull();
    }

    @Test
    void jobStatusReadsColumnsAndCountsPackedPlans() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(42L);
        when(rs.getString("job_uuid")).thenReturn("job-uuid");
        when(rs.getLong("trip_id")).thenReturn(10L);
        when(rs.getString("objective")).thenReturn("MAX_VOLUME");
        when(rs.getInt("time_limit_sec")).thenReturn(60);
        when(rs.getString("status")).thenReturn("NO_SOLUTION");
        when(rs.getLong("computation_ms")).thenReturn(120L);
        when(rs.getTimestamp(eq("created_at"), any(Calendar.class)))
                .thenReturn(Timestamp.from(Instant.parse("2026-11-05T03:15:00Z")));
        when(rs.getInt("packed_plans")).thenReturn(0);
        rows(rs);

        Optional<OptimizationJobStatusResponse> status = repository.findJobStatus(42L);

        assertThat(status).hasValueSatisfying(job -> {
            assertThat(job.getJobId()).isEqualTo("job-uuid");
            assertThat(job.getTripId()).isEqualTo(10L);
            assertThat(job.getTimeLimitSec()).isEqualTo(60);
            assertThat(job.getStatus()).isEqualTo("NO_SOLUTION");
            assertThat(job.getComputationMs()).isEqualTo(120L);
            assertThat(job.getCreatedAt()).isEqualTo(Instant.parse("2026-11-05T03:15:00Z"));
            assertThat(job.getPackedPlanCount()).isZero();
        });
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), eq(Map.of("id", 42L)), any(RowMapper.class));
        assertThat(sql.getValue()).contains("COALESCE(lp.packed_items_count, 0) > 0");
    }
}
