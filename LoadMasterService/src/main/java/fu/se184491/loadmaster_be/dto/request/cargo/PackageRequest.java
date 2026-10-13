package fu.se184491.loadmaster_be.dto.request.cargo;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageRequest {

    private Long orderId;

    private Long packageTypeId;

    @Size(max = 100)
    private String packageCode;

    @Positive
    private Integer actualLength;

    @Positive
    private Integer actualWidth;

    @Positive
    private Integer actualHeight;

    @NotNull(message = "Khối lượng kiện không được để trống")
    @Positive(message = "Khối lượng kiện phải lớn hơn 0")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal actualWeightKg;

    @NotNull(message = "Loại hàng không được để trống")
    private HandlingClass handlingClass;

    @NotBlank(message = "Điểm đến không được để trống")
    @Size(max = 500)
    private String destination;

    private Boolean isPinned;
}