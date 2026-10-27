package fu.se184491.loadmaster_be.dto.request.cargo;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    // Thông tin liên kết hiện có
    private Long orderId;

    private Long packageTypeId;

    @Positive
    private Long deliveryRequirementId;


    // Mã và tên kiện
    @Size(max = 100)
    private String packageCode;

    @Size(max = 150)
    private String packageName;

    // Số lượng kiện vật lý cần tạo
    @Builder.Default
    @NotNull
    @Min(value = 1, message = "Số lượng phải lớn hơn hoặc bằng 1")
    @Max(value = 1000, message = "Tối đa 1000 kiện mỗi lần")
    private Integer quantity = 1;

    @Positive
    @Digits(integer = 8, fraction = 2)
    private BigDecimal actualLength;

    @Positive
    @Digits(integer = 8, fraction = 2)
    private BigDecimal actualWidth;

    @Positive
    @Digits(integer = 8, fraction = 2)
    private BigDecimal actualHeight;

    @NotNull(message = "Khối lượng kiện không được để trống")
    @Positive(message = "Khối lượng kiện phải lớn hơn 0")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal actualWeightKg;

    @NotNull(message = "Loại hàng không được để trống")
    private HandlingClass handlingClass;

    @NotBlank(message = "Điểm đến không được để trống")
    @Size(max = 255)
    private String destination;

    private Boolean isPinned;

    // Ghi chú
    private String note;

    // Thông số xếp hàng riêng của từng kiện
    @Size(max = 100)
    private String orientation;

    private Boolean uprightOnly;

    @Min(value = 0, message = "Mức dễ vỡ không được âm")
    private Integer fragilityLevel;

    private Boolean stackable;

    @DecimalMin(value = "0.0",
            message = "Tải trên tối đa không được âm")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal maxTopLoadKg;

    @Min(value = 1, message = "Số tầng tối đa phải >= 1")
    private Integer maxLayers;

    @DecimalMin(value = "0.0",
            message = "Tỷ lệ đỡ đáy không được âm")
    @DecimalMax(value = "1.0",
            message = "Tỷ lệ đỡ đáy không vượt quá 1")
    private BigDecimal minSupportRatio;

    private Integer priority;

    private Boolean mustLoad;

    @Size(max = 100)
    private String packingGroup;
}
