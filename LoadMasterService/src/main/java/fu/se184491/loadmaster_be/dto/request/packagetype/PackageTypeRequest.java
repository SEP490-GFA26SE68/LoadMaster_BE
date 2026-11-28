package fu.se184491.loadmaster_be.dto.request.packagetype;

import jakarta.validation.constraints.*;
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

    @Size(max = 50, message = "Mã loại kiện tối đa 50 ký tự")
    private String typeCode;

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
    @DecimalMin(
            value = "0.0",
            inclusive = true,
            message = "Tải trọng chồng tối đa phải lớn hơn hoặc bằng 0"
    )
    private BigDecimal maxStackingWeightKg;

    // σ, kg/m² (spec H7). Tuỳ chọn: trống = chưa khai. Cập nhật mà không gửi trường này thì giữ giá trị cũ.
    @DecimalMin(value = "0.0", message = "Sức chịu nén mặt trên phải lớn hơn hoặc bằng 0")
    @Digits(integer = 10, fraction = 2, message = "Sức chịu nén mặt trên tối đa 10 chữ số phần nguyên, 2 chữ số thập phân")
    private BigDecimal topLoadCapacityKgM2;

    // ρ (spec H8). Tuỳ chọn, 0,5–1. Cập nhật mà không gửi trường này thì giữ giá trị cũ.
    @DecimalMin(value = "0.5", message = "Tỉ lệ đáy được đỡ tối thiểu phải từ 0,5 đến 1")
    @DecimalMax(value = "1.0", message = "Tỉ lệ đáy được đỡ tối thiểu phải từ 0,5 đến 1")
    private BigDecimal minSupportRatio;

    @Builder.Default
    private Boolean isFragile = false;

    @Builder.Default
    private Boolean rotationAllowed = true;

    @Builder.Default
    private Boolean allowRotateX = true;

    @Builder.Default
    private Boolean allowRotateY = true;

    @Builder.Default
    private Boolean allowRotateZ = true;
}
