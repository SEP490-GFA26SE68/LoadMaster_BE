package fu.se184491.loadmaster_be.dto.request.support;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateTrialAccountRequest {

    private Long contactId;

    @NotBlank(message = "Họ và tên không được để trống")
    private String fullName;

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    private String email;

    private String phoneNumber;

    private String companyName;

    private Long interestedPlanId;

    private String note;
}
