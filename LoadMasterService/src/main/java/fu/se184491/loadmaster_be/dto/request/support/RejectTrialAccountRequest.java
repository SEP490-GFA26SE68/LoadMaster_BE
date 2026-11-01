package fu.se184491.loadmaster_be.dto.request.support;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RejectTrialAccountRequest {

    private String reason;
}
