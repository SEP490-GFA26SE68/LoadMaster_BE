package fu.se184491.loadmaster_be.dto.request.pickup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/** Tạo yêu cầu nhận hàng dọc đường; hạn giao là giờ Việt Nam, không múi giờ, có thể bỏ trống. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatePickupRequest {

    @NotNull(message = "Điểm nhận không được để trống")
    @Valid
    private PickupPointRequest pickup;

    @NotNull(message = "Điểm giao không được để trống")
    @Valid
    private PickupPointRequest delivery;

    private LocalDateTime deadline;

    @Size(max = 50, message = "Tối đa 50 kiện mỗi yêu cầu")
    @Valid
    private List<PickupPackageRequest> packages;
}
