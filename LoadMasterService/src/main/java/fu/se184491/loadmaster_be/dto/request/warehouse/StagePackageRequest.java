package fu.se184491.loadmaster_be.dto.request.warehouse;

import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Kho đối chiếu một kiện vào khu chờ bằng nhãn: {@code QR} (quét) hoặc {@code CODE} (gõ mã); vắng là {@code QR}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StagePackageRequest {

    private StagingMethod method;
}
