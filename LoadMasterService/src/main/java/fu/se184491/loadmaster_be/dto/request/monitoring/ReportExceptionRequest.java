package fu.se184491.loadmaster_be.dto.request.monitoring;

import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportExceptionRequest {

    @NotNull(message = "Loại sự cố không được để trống")
    private TripExceptionType type;

    @NotBlank(message = "Mô tả sự cố không được để trống")
    @Size(max = 300, message = "Mô tả sự cố tối đa 300 ký tự")
    private String description;

    @NotNull(message = "Số phút chậm không được để trống")
    @Min(value = 0, message = "Số phút chậm phải >= 0")
    @Max(value = 480, message = "Số phút chậm tối đa 480")
    private Integer delayMinutes;
}
