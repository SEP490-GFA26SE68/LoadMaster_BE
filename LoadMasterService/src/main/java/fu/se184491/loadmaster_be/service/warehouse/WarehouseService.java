package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.dto.request.warehouse.RecordDeviationRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.StartLoadingRequest;
import fu.se184491.loadmaster_be.dto.response.warehouse.CompleteLoadingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.ConfirmPlacementResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.DeviationResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.StartLoadingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.WarehouseTaskResponse;

import fu.se184491.loadmaster_be.constant.cargo.LabelMethod;

import java.util.List;

public interface WarehouseService {

    default List<WarehouseTaskResponse> getTasks(Long companyId, Long workerId) {
        return getTasks(companyId, workerId, false);
    }

    /** {@code includeAwaitingReplan}: thêm chuyến quay về lập kế hoạch vì kiện thiếu / hỏng (màn web; app di động không hỏi). */
    List<WarehouseTaskResponse> getTasks(Long companyId, Long workerId, boolean includeAwaitingReplan);

    StartLoadingResponse startLoading(Long tripId, StartLoadingRequest request);

    /** Phiên xếp của chuyến Đang xếp hàng, cùng dạng với lúc bắt đầu xếp, kèm trạng thái từng kiện. */
    StartLoadingResponse getSession(Long tripId);

    /** Xếp một kiện, đối chiếu bằng nhãn theo {@code method} (vắng là QR). */
    ConfirmPlacementResponse confirmPlacement(Long placementId, Long workerId, LabelMethod method);

    default ConfirmPlacementResponse confirmPlacement(Long placementId, Long workerId) {
        return confirmPlacement(placementId, workerId, null);
    }

    DeviationResponse recordDeviation(Long placementId, RecordDeviationRequest request);

    CompleteLoadingResponse completeLoading(Long tripId);
}

