package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStartResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStatusResponse;

/** Gửi job tối ưu xếp hàng 3D cho dịch vụ tối ưu và đọc trạng thái job, theo công ty của phiên. */
public interface LoadingOptimizationService {

    /**
     * Kiểm credit rồi gửi job kèm mã công ty và hạng gói để dịch vụ tối ưu trừ credit
     * và chọn thuật toán theo gói.
     */
    OptimizationJobStartResponse startLoadingOptimization(Long tripId);

    /** Trạng thái job (đọc từ DB), sau khi kiểm job thuộc công ty của phiên. */
    OptimizationJobStatusResponse getJobStatus(String jobUuid);
}
