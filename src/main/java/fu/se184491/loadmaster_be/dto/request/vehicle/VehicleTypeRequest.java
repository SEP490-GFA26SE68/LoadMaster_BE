package fu.se184491.loadmaster_be.dto.request.vehicle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleTypeRequest {

    @NotBlank(message = "Tên loại xe không được để trống")
    private String name;

    @NotNull(message = "Chiều dài thùng không được để trống")
    @Positive(message = "Chiều dài thùng phải lớn hơn 0")
    private Integer innerLength;

    @NotNull(message = "Chiều rộng thùng không được để trống")
    @Positive(message = "Chiều rộng thùng phải lớn hơn 0")
    private Integer innerWidth;

    @NotNull(message = "Chiều cao thùng không được để trống")
    @Positive(message = "Chiều cao thùng phải lớn hơn 0")
    private Integer innerHeight;

    @NotNull(message = "Tải trọng tối đa không được để trống")
    @Positive(message = "Tải trọng tối đa phải lớn hơn 0")
    private BigDecimal maxPayloadKg;

    @NotNull(message = "Chiều rộng cửa không được để trống")
    @Positive(message = "Chiều rộng cửa phải lớn hơn 0")
    private Integer doorWidth;

    @NotNull(message = "Chiều cao cửa không được để trống")
    @Positive(message = "Chiều cao cửa phải lớn hơn 0")
    private Integer doorHeight;
}
