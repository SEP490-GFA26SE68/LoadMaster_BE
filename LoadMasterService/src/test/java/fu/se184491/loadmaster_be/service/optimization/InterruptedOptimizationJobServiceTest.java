package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStatusResponse;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository.JobOwner;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanWriteRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.optimization.Impl.InterruptedOptimizationJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Job kẹt PENDING / RUNNING quá giới hạn của job + khoảng dư (dịch vụ tối ưu dừng giữa chừng): đánh dấu FAILED
 * JOB_INTERRUPTED và hoàn credit đúng một lần.
 */
@ExtendWith(MockitoExtension.class)
class InterruptedOptimizationJobServiceTest {

    private static final Instant CREATED = Instant.parse("2026-09-14T01:00:00Z");
    private static final JobOwner OWNER = new JobOwner(5L, "job-uuid", 10L, 1L);

    @Mock LoadPlanWriteRepository writeRepository;
    @Mock CreditService creditService;

    private InterruptedOptimizationJobService service;

    @BeforeEach
    void setUp() {
        service = new InterruptedOptimizationJobService(writeRepository, creditService, 300);
    }

    private static OptimizationJobStatusResponse job(String status) {
        return OptimizationJobStatusResponse.builder()
                .id(5L).jobId("job-uuid").status(status).timeLimitSec(60).createdAt(CREATED).build();
    }

    @Test
    void legacyUnfinishedJobsNeverInferMissingOwnershipFromAge() {
        for (String state : java.util.List.of("PENDING", "RUNNING", "COMPLETED")) {
            assertThat(service.failIfInterrupted(OWNER, job(state), CREATED.plusSeconds(86400))).isFalse();
        }
        org.mockito.Mockito.verifyNoInteractions(writeRepository, creditService);
    }
}
