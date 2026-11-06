package fu.se184491.loadmaster_be.service.pickup;

import fu.se184491.loadmaster_be.dto.request.pickup.ApprovePickupRequest;
import fu.se184491.loadmaster_be.dto.request.pickup.CreatePickupRequest;
import fu.se184491.loadmaster_be.dto.request.pickup.RejectPickupRequest;
import fu.se184491.loadmaster_be.dto.response.pickup.PickupRequestResponse;

import java.util.List;

/** Yêu cầu nhận hàng dọc đường của một chuyến Đang vận chuyển: tạo, kiểm mười luật, duyệt (chèn điểm, tạo kiện), từ chối. */
public interface PickupRequestService {

    List<PickupRequestResponse> list(String tripIdOrCode);

    PickupRequestResponse get(String tripIdOrCode, Long pickupId);

    PickupRequestResponse create(String tripIdOrCode, CreatePickupRequest request);

    PickupRequestResponse validate(String tripIdOrCode, Long pickupId);

    PickupRequestResponse approve(String tripIdOrCode, Long pickupId, ApprovePickupRequest request);

    PickupRequestResponse reject(String tripIdOrCode, Long pickupId, RejectPickupRequest request);
}
