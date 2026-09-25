package fu.se184491.loadmaster_be.dto.request.warehouse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class RecordDeviationRequest {

    @NotNull(message = "Tọa độ X thực tế không được để trống")
    private Integer actualPosX;

    @NotNull(message = "Tọa độ Y thực tế không được để trống")
    private Integer actualPosY;

    @NotNull(message = "Tọa độ Z thực tế không được để trống")
    private Integer actualPosZ;

    @NotBlank(message = "Lý do sai lệch không được để trống")
    private String reason;

    private Long reportedBy;
}
