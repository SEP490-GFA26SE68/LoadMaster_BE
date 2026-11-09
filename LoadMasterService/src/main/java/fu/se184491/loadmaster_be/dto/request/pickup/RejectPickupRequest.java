package fu.se184491.loadmaster_be.dto.request.pickup;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Từ chối yêu cầu nhận; lý do bắt buộc. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RejectPickupRequest {

    @Size(max = 300, message = "Lý do tối đa 300 ký tự")
    private String reason;
}
