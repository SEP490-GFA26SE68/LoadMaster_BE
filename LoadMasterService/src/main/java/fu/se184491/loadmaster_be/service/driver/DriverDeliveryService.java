package fu.se184491.loadmaster_be.service.driver;

import fu.se184491.loadmaster_be.dto.request.driver.ManualUnloadRequest;
import fu.se184491.loadmaster_be.dto.request.driver.RejectManualConfirmRequest;
import fu.se184491.loadmaster_be.dto.request.driver.ReportDeliveryIssueRequest;
import fu.se184491.loadmaster_be.dto.response.driver.DeliveryIssueResponse;
import fu.se184491.loadmaster_be.dto.response.driver.DriverLoadPlanResponse;
import fu.se184491.loadmaster_be.dto.response.driver.DriverTripStopsResponse;
import fu.se184491.loadmaster_be.dto.response.driver.ManualConfirmationResponse;

import java.util.List;

/**
 * Việc giao hàng của tài xế ngoài luồng dỡ cơ bản ({@link DriverService}): phương án kho đã xếp cho khung 3D, giờ đến và hạn giao của
 * các điểm, sự cố theo kiện, xác nhận tay khi dỡ — và phần duyệt xác nhận tay của điều phối viên.
 */
public interface DriverDeliveryService {

    /** Phương án kho đã xếp lên xe của chuyến của chính tài xế (Đang xếp hàng hoặc Đang vận chuyển). */
    DriverLoadPlanResponse getLoadPlan(String tripIdOrCode);

    /** Các điểm của chuyến của chính tài xế kèm giờ đến kế hoạch, hạn giao và giờ đến dự kiến từ GPS. */
    DriverTripStopsResponse getStops(String tripIdOrCode);

    /** Tài xế báo sự cố cho một kiện ở điểm đang giao (đã báo đến). Khách từ chối: kiện về chưa dỡ, ở lại xe. */
    DeliveryIssueResponse reportIssue(Long packageId, ReportDeliveryIssueRequest request);

    /** Tài xế xác nhận tay một kiện ở điểm đang giao: kiện ghi đã dỡ, xác nhận chờ điều phối viên duyệt. */
    ManualConfirmationResponse requestManualUnload(Long packageId, ManualUnloadRequest request);

    /** Xác nhận tay còn chờ duyệt của một chuyến của công ty người đăng nhập. */
    List<ManualConfirmationResponse> listPendingManualConfirmations(String tripIdOrCode);

    /** Điều phối viên duyệt: kiện giữ kết quả đã dỡ. */
    ManualConfirmationResponse approveManualConfirmation(String tripIdOrCode, Long confirmationId);

    /** Điều phối viên từ chối kèm lý do: kiện về chưa dỡ, tài xế phải đối chiếu lại. */
    ManualConfirmationResponse rejectManualConfirmation(String tripIdOrCode, Long confirmationId, RejectManualConfirmRequest request);
}
