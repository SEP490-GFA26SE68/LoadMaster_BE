package fu.se184491.loadmaster_be.dto.request.pickup;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Duyệt yêu cầu nhận; còn luật không đạt thì bắt buộc lý do vượt luật. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovePickupRequest {

    @Size(max = 300, message = "Lý do tối đa 300 ký tự")
    private String overrideReason;
}
