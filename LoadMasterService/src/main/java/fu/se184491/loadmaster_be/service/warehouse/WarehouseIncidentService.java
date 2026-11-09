package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.dto.request.driver.ManualUnloadRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.RecordSealRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.ReportDamageRequest;
import fu.se184491.loadmaster_be.dto.response.driver.ManualConfirmationResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.DamageResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.SealResponse;

/** Việc của kho lúc xếp ngoài xác nhận vị trí: xác nhận tay một vị trí, báo kiện hỏng, ghi số seal sau khi xếp xong. */
public interface WarehouseIncidentService {

    /** Kho xác nhận tay vị trí kế tiếp (nhãn rách, QR không đọc được…); kiện tính là đã xếp, điều phối viên duyệt hoặc từ chối sau. */
    ManualConfirmationResponse confirmPlacementManually(Long placementId, ManualUnloadRequest request);

    /**
     * Kiện của vị trí kế tiếp bị hỏng. Không kiện nào tựa lên nó trong phương án: kiện bị bỏ lại kho, kho xếp tiếp. Có kiện tựa lên: chuyến
     * quay về Đã lập kế hoạch, chờ điều phối viên tối ưu lại.
     */
    DamageResponse reportDamaged(Long placementId, ReportDamageRequest request);

    /** Ghi (hoặc sửa) số seal của chuyến đã xếp xong, trước khi tài xế xuất phát. */
    SealResponse recordSeal(Long tripId, RecordSealRequest request);
}
