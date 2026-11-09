package fu.se184491.loadmaster_be.dto.request.driver;

import fu.se184491.loadmaster_be.constant.driver.ManualConfirmReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Xác nhận tay một kiện khi dỡ; {@code OTHER} bắt buộc ghi chú. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManualUnloadRequest {

    @NotNull(message = "Lý do xác nhận tay không được để trống")
    private ManualConfirmReason reason;

    @Size(max = 300, message = "Ghi chú tối đa 300 ký tự")
    private String note;
}
