package fu.se184491.loadmaster_be.dto.request.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartLoadingRequest {

    private Long workerId;

    /**
     * Phiên có bước soạn hàng: kho đối chiếu mọi kiện vào khu chờ trước, soạn đủ (hoặc báo thiếu đã được quyết) mới xếp được. Vắng hay
     * false là phiên như cũ, xếp ngay (app kho trên điện thoại không gửi trường này).
     */
    private Boolean staging;
}
