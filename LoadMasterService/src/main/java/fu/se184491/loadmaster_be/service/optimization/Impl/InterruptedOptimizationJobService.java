package fu.se184491.loadmaster_be.service.optimization.Impl;

import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStatusResponse;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository.JobOwner;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanWriteRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Dịch vụ tối ưu giữ job trong bộ nhớ: khởi động lại giữa chừng thì job kẹt PENDING / RUNNING mãi và credit đã giữ
 * không được hoàn. Lúc đọc trạng thái job, job chờ / chạy quá thời gian cho phép (giới hạn của job + khoảng dư) được
 * đánh dấu FAILED với mã {@link #FAILURE_CODE} và hoàn credit qua luồng hoàn sẵn có (hoàn theo mã job, một lần).
 */
@Service
@Slf4j
public class InterruptedOptimizationJobService {

    public static final String FAILURE_CODE = "JOB_INTERRUPTED";

    private static final Set<String> UNFINISHED = Set.of("PENDING", "RUNNING");
    private static final int DEFAULT_TIME_LIMIT_SEC = 60;

    private final LoadPlanWriteRepository writeRepository;
    private final CreditService creditService;
    private final Duration grace;

    public InterruptedOptimizationJobService(
            LoadPlanWriteRepository writeRepository,
            CreditService creditService,
            @Value("${app.optimization.interrupted-job-grace-seconds:300}") long graceSeconds) {
        this.writeRepository = writeRepository;
        this.creditService = creditService;
        this.grace = Duration.ofSeconds(graceSeconds);
    }

    /** Job đã quá hạn và được đánh dấu FAILED ở lượt này thì true (credit đã hoàn trong cùng giao dịch). */
    @Transactional
    public boolean failIfInterrupted(JobOwner owner, OptimizationJobStatusResponse status, Instant now) {
        if (status == null || !UNFINISHED.contains(status.getStatus()) || status.getCreatedAt() == null) {
            return false;
        }
        int limitSec = status.getTimeLimitSec() == null ? DEFAULT_TIME_LIMIT_SEC : status.getTimeLimitSec();
        Instant expiresAt = status.getCreatedAt().plusSeconds(limitSec).plus(grace);
        if (!now.isAfter(expiresAt)) {
            return false;
        }
        // Chỉ lượt đổi được trạng thái mới hoàn credit: hai lượt đọc cùng lúc không hoàn hai lần.
        if (writeRepository.failUnfinishedJob(owner.jobId(), FAILURE_CODE) != 1) {
            return false;
        }
        log.warn("Optimization job {} (trip {}) stuck in {} past its time limit; marked FAILED {}",
                owner.jobUuid(), owner.tripId(), status.getStatus(), FAILURE_CODE);
        creditService.refundCredit(owner.companyId(), owner.jobUuid());
        return true;
    }
}
