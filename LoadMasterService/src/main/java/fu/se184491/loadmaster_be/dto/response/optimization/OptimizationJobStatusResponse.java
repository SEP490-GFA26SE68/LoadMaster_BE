package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

/** Canonical job status. Credit settlement is reported separately by creditStatus;
 * FAILED or a failureCode alone does not prove that the ledger was refunded. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptimizationJobStatusResponse {
    private Long id;
    private String jobId;
    private Long tripId;
    private String objective;
    private Integer timeLimitSec;
    private String status;
    private Long computationMs;
    private Instant createdAt;
    private Integer packedPlanCount;
    private Integer pinnedCount;
    private String runAlgorithm;
    private String failureCode;
    private String creditStatus;
    /** Cảnh báo dữ liệu đầu vào của lần chạy (kiện thiếu σ, kiện không có loại kiện …); không chặn job. */
    private List<DataWarningResponse> dataWarnings;
    /** Thời gian đã chạy (ms, theo đồng hồ PostgreSQL) khi job đang RUNNING và đã bắt đầu; null trong mọi trường hợp khác. */
    private Long elapsedMs;
    /** Tiến độ sống do OptimizeService báo; null khi job chưa báo gì (PENDING, engine từ xa, job cũ). */
    private OptimizationJobProgressResponse progress;
}
