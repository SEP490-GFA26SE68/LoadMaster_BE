package fu.se184491.loadmaster_be.entity.optimization;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizationJobTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Test
    void jobCreatedByJavaGetsVietnamCreationTime() {
        OptimizationJob job = OptimizationJob.builder().jobUuid("rerun").build();

        job.fillCreatedAt();

        LocalDateTime now = LocalDateTime.now(VIETNAM);
        assertThat(job.getCreatedAt()).isNotNull();
        assertThat(Duration.between(job.getCreatedAt(), now).abs()).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void existingCreationTimeIsKept() {
        LocalDateTime written = LocalDateTime.of(2026, 11, 12, 9, 30);
        OptimizationJob job = OptimizationJob.builder().createdAt(written).build();

        job.fillCreatedAt();

        assertThat(job.getCreatedAt()).isEqualTo(written);
    }
}
