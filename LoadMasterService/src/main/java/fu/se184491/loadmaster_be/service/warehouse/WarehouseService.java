package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.dto.request.warehouse.RecordDeviationRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.StartLoadingRequest;
import fu.se184491.loadmaster_be.dto.response.warehouse.CompleteLoadingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.ConfirmPlacementResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.DeviationResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.StartLoadingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.WarehouseTaskResponse;

import java.util.List;

public interface WarehouseService {

    List<WarehouseTaskResponse> getTasks(Long companyId, Long workerId);

    StartLoadingResponse startLoading(Long tripId, StartLoadingRequest request);

    ConfirmPlacementResponse confirmPlacement(Long placementId, Long workerId);

    DeviationResponse recordDeviation(Long placementId, RecordDeviationRequest request);

    CompleteLoadingResponse completeLoading(Long tripId);
}

