package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Trạng thái một job tối ưu xếp hàng.
 * {@code jobId}: mã job (UUID). {@code status}: PENDING | RUNNING | COMPLETED | PARTIAL | NO_SOLUTION | FAILED
 * (job cũ còn có TIMEOUT). {@code packedPlanCount}: số phương án đã lưu của job có kiện xếp được
 * (lần chạy ba phương án: 3; NO_SOLUTION hay FAILED: 0). {@code pinnedCount}: số kiện đã ghim mà lần chạy giữ
 * nguyên (0 khi lần chạy không giữ ghim). {@code runAlgorithm}: thuật toán của lần chạy — hạng của gói
 * (EP_DBLF | EP_DBLF_GA | EP_DBLF_GA_AI); thuật toán của từng phương án nằm ở {@code algorithmName} của phương án.
 * {@code failureCode}: lý do của FAILED khi biết, ví dụ {@code JOB_INTERRUPTED} — job kẹt chờ / đang chạy quá hạn vì
 * dịch vụ tối ưu dừng giữa chừng, credit đã giữ được hoàn; null với mọi trạng thái khác.
 */
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
}
