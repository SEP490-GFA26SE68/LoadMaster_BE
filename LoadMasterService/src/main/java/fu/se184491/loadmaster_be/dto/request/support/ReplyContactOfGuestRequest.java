package fu.se184491.loadmaster_be.dto.request.support;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReplyContactOfGuestRequest {

    private String subject;

    @NotBlank(message = "Nội dung phản hồi không được để trống")
    private String message;
}
