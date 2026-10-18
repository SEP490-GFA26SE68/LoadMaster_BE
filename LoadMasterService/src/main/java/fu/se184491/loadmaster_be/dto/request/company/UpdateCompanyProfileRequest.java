package fu.se184491.loadmaster_be.dto.request.company;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateCompanyProfileRequest {

    @NotBlank(message = "Tên công ty không được để trống")
    private String companyName;

    private String address;

    @Email(message = "Email liên hệ không đúng định dạng")
    private String contactEmail;

    private String contactPhone;
}
