package fu.se184491.loadmaster_be.dto.request.support;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuestTrialInitRequest {

    @NotBlank(message = "Tên doanh nghiệp không được để trống")
    private String companyName;

    @NotBlank(message = "Mã số thuế không được để trống")
    private String taxCode;

    private String companyAddress;

    @NotBlank(message = "Họ và tên người đại diện không được để trống")
    private String fullName;

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    private String email;

    private String phoneNumber;

    private Long interestedPlanId;

    private String note;
}

