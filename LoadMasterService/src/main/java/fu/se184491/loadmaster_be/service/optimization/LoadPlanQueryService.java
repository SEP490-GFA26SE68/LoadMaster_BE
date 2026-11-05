package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStatusResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.TripLoadPlansResponse;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository.JobOwner;

import java.util.List;

/**
 * Đọc và duyệt phương án xếp hàng theo công ty của người đăng nhập.
 * Phương án, job hay chuyến của công ty khác đọc là không tồn tại.
 */
public interface LoadPlanQueryService {

    TripLoadPlansResponse getTripLoadPlans(Long tripId);

    LoadPlanDetailResponse getPlan(Long planId);

    List<LoadPlanDetailResponse> getJobPlans(String jobUuid);

    /** Trạng thái job thuộc công ty của phiên, không thì {@code OPTIMIZATION_JOB_NOT_FOUND}. */
    OptimizationJobStatusResponse getJobStatus(String jobUuid);

    /** Job thuộc công ty của phiên, không thì {@code OPTIMIZATION_JOB_NOT_FOUND}. */
    JobOwner requireOwnedJob(String jobUuid);

    /** Phương án thuộc công ty của phiên, không thì {@code LOAD_PLAN_NOT_FOUND}. */
    void requireOwnedPlan(Long planId);

    /** Duyệt phương án qua dịch vụ tối ưu; không đổi trạng thái chuyến. */
    LoadPlanDetailResponse approvePlan(Long planId);
}
