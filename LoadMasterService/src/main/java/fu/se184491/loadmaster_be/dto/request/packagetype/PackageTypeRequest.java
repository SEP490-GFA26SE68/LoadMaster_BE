package fu.se184491.loadmaster_be.dto.request.packagetype;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PackageTypeRequest {

    @NotBlank(message = "Tên loại kiện không được để trống")
    private String name;

    @NotNull(message = "Chiều dài không được để trống")
    @Positive(message = "Chiều dài phải lớn hơn 0")
    private Integer length;

    @NotNull(message = "Chiều rộng không được để trống")
    @Positive(message = "Chiều rộng phải lớn hơn 0")
    private Integer width;

    @NotNull(message = "Chiều cao không được để trống")
    @Positive(message = "Chiều cao phải lớn hơn 0")
    private Integer height;

    @NotNull(message = "Trọng lượng không được để trống")
    @Positive(message = "Trọng lượng phải lớn hơn 0")
    private BigDecimal weightKg;

    @NotNull(message = "Tải trọng chồng tối đa không được để trống")
    @DecimalMin(value = "0.0", inclusive = true, message = "Tải trọng chồng tối đa phải lớn hơn hoặc bằng 0")
    private BigDecimal maxStackingWeightKg;

    @Builder.Default
    private Boolean isFragile = false;
}
