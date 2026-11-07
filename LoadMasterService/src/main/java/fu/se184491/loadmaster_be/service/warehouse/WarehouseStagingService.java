package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.dto.request.driver.ManualUnloadRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.ReportShortageRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.ResolveShortageRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.StagePackageRequest;
import fu.se184491.loadmaster_be.dto.response.warehouse.StagingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.StagingShortageResponse;

import java.util.List;

/** Soạn hàng của kho trước khi xếp và báo thiếu kiện; điều phối viên quyết báo thiếu. */
public interface WarehouseStagingService {

    /** Kho đối chiếu kiện vào khu chờ bằng nhãn (quét hoặc gõ mã). Kiện đã soạn thì không ghi lại. */
    StagingResponse stage(Long tripId, Long packageId, StagePackageRequest request);

    /** Kho xác nhận tay kiện vào khu chờ; kiện tính là đã soạn, điều phối viên duyệt hoặc từ chối sau. */
    StagingResponse stageManually(Long tripId, Long packageId, ManualUnloadRequest request);

    /** Kho báo không tìm thấy kiện ở kho. */
    StagingShortageResponse reportShortage(Long tripId, Long packageId, ReportShortageRequest request);

    /** Báo thiếu chờ điều phối viên quyết của chuyến. */
    List<StagingShortageResponse> listOpenShortages(String tripIdOrCode);

    /** Điều phối viên quyết: tìm tiếp, hoặc bỏ kiện khỏi chuyến (chuyến quay về Đã lập kế hoạch, phương án lỗi thời). */
    StagingShortageResponse resolveShortage(String tripIdOrCode, Long shortageId, ResolveShortageRequest request);
}
