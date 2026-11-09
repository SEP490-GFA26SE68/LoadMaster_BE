package fu.se184491.loadmaster_be.dto.request.pickup;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Điểm nhận hoặc điểm giao của một yêu cầu nhận hàng dọc đường. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PickupPointRequest {

    @NotBlank(message = "Tên điểm không được để trống")
    @Size(max = 150, message = "Tên điểm tối đa 150 ký tự")
    private String name;

    @NotBlank(message = "Địa chỉ không được để trống")
    @Size(max = 255, message = "Địa chỉ tối đa 255 ký tự")
    private String address;

    @NotNull(message = "Vĩ độ không được để trống")
    @DecimalMin(value = "-90") @DecimalMax(value = "90")
    private BigDecimal latitude;

    @NotNull(message = "Kinh độ không được để trống")
    @DecimalMin(value = "-180") @DecimalMax(value = "180")
    private BigDecimal longitude;
}
