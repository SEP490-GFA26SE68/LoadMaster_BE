package fu.se184491.loadmaster_be.dto.request.driver;

import fu.se184491.loadmaster_be.constant.driver.DeliveryIssueKind;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Sự cố của một kiện tại điểm đang giao; {@code OTHER} bắt buộc ghi chú. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportDeliveryIssueRequest {

    @NotNull(message = "Loại sự cố không được để trống")
    private DeliveryIssueKind kind;

    @Size(max = 300, message = "Ghi chú tối đa 300 ký tự")
    private String note;
}
