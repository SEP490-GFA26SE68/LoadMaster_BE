package fu.se184491.loadmaster_be.dto.request.pickup;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
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

/** Một kiện của yêu cầu nhận: kích thước cm, khối lượng kg; vắng loại hàng là hàng thường. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PickupPackageRequest {

    @NotBlank(message = "Mã kiện của bên gửi không được để trống")
    @Size(max = 90, message = "Mã kiện tối đa 90 ký tự")
    private String packageCode;

    @NotNull @DecimalMin(value = "0.1")
    private BigDecimal lengthCm;

    @NotNull @DecimalMin(value = "0.1")
    private BigDecimal widthCm;

    @NotNull @DecimalMin(value = "0.1")
    private BigDecimal heightCm;

    @NotNull @DecimalMin(value = "0.01")
    private BigDecimal weightKg;

    private HandlingClass handlingClass;
}
