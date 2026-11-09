package fu.se184491.loadmaster_be.dto.request.warehouse;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Kho báo kiện của bước xếp hiện tại bị hỏng; ghi chú tuỳ chọn. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportDamageRequest {

    @Size(max = 300, message = "Ghi chú tối đa 300 ký tự")
    private String note;
}
