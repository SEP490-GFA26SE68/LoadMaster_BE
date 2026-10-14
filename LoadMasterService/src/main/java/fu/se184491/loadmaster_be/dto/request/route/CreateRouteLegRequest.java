package fu.se184491.loadmaster_be.dto.request.route;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRouteLegRequest {

    @NotNull(message = "Số thứ tự chặng không được để trống")
    @Positive(message = "Số thứ tự chặng phải lớn hơn 0")
    private Integer legSequence;

    private Long startStopId;

    @NotNull(message = "Điểm kết thúc chặng không được để trống")
    private Long endStopId;

    @NotNull(message = "Khoảng cách chặng không được để trống")
    @PositiveOrZero(message = "Khoảng cách chặng phải lớn hơn hoặc bằng 0")
    private Integer distanceMeters;

    @NotNull(message = "Thời gian chặng không được để trống")
    @PositiveOrZero(message = "Thời gian chặng phải lớn hơn hoặc bằng 0")
    private Integer durationSeconds;

    private String polyline;
}
