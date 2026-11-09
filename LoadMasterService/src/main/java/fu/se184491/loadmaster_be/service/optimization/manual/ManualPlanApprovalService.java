package fu.se184491.loadmaster_be.service.optimization.manual;

import fu.se184491.loadmaster_be.dto.request.optimization.ApproveEditsRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;

/** Duyệt bản chỉnh tay của một phương án: kiểm cách xếp mới ở máy chủ rồi lưu thành bản duyệt mới. */
public interface ManualPlanApprovalService {

    LoadPlanDetailResponse approveEdits(Long planId, ApproveEditsRequest request);
}
