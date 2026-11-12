package fu.se184491.loadmaster_be.dto.request.cargo;

import fu.se184491.loadmaster_be.constant.cargo.LabelMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Thân tuỳ chọn của lệnh đối chiếu kiện bằng nhãn: {@code QR} (quét) hoặc {@code CODE} (gõ mã); vắng là {@code QR}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabelConfirmRequest {

    private LabelMethod method;

    /** Cách đối chiếu của thân lệnh, vắng thân hoặc vắng trường là {@code QR}. */
    public static LabelMethod methodOf(LabelConfirmRequest request) {
        return LabelMethod.orQr(request == null ? null : request.getMethod());
    }
}
