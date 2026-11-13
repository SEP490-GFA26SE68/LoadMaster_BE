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
    void runningJobPastLimitAndGraceIsFailedAndRefunded() {
        when(writeRepository.failUnfinishedJob(5L, "JOB_INTERRUPTED")).thenReturn(1);

        boolean failed = service.failIfInterrupted(OWNER, job("RUNNING"), CREATED.plusSeconds(361));

        assertThat(failed).isTrue();
        verify(creditService).refundCredit(1L, "job-uuid");
    }

    @Test
    void pendingJobPastLimitIsAlsoClosed() {
        when(writeRepository.failUnfinishedJob(5L, "JOB_INTERRUPTED")).thenReturn(1);

        assertThat(service.failIfInterrupted(OWNER, job("PENDING"), CREATED.plusSeconds(3600))).isTrue();
    }

    @Test
    void jobStillWithinLimitAndGraceIsLeftRunning() {
        assertThat(service.failIfInterrupted(OWNER, job("RUNNING"), CREATED.plusSeconds(360))).isFalse();

        verify(writeRepository, never()).failUnfinishedJob(anyLong(), anyString());
        verify(creditService, never()).refundCredit(anyLong(), anyString());
    }

    @Test
    void finishedJobIsNeverTouched() {
        assertThat(service.failIfInterrupted(OWNER, job("COMPLETED"), CREATED.plusSeconds(86_400))).isFalse();

        verify(writeRepository, never()).failUnfinishedJob(anyLong(), anyString());
    }

    @Test
    void secondConcurrentReaderDoesNotRefundAgain() {
        // Lượt kia đã đổi trạng thái: UPDATE có điều kiện không đổi dòng nào
        when(writeRepository.failUnfinishedJob(5L, "JOB_INTERRUPTED")).thenReturn(0);

        assertThat(service.failIfInterrupted(OWNER, job("RUNNING"), CREATED.plusSeconds(3600))).isFalse();

        verify(creditService, never()).refundCredit(anyLong(), anyString());
    }
}
