package fu.se184491.loadmaster_be.dto.request.company;

import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyRequest {

    @NotBlank(message = "Mã công ty không được để trống")
    @Size(max = 50, message = "Mã công ty không được vượt quá 50 ký tự")
    private String companyCode;

    @NotBlank(message = "Tên công ty không được để trống")
    @Size(max = 255, message = "Tên công ty không được vượt quá 255 ký tự")
    private String companyName;

    @NotBlank(message = "Mã số thuế không được để trống")
    @Size(max = 50, message = "Mã số thuế không được vượt quá 50 ký tự")
    private String taxCode;

    @NotBlank(message = "Email thanh toán không được để trống")
    @Email(message = "Email thanh toán không đúng định dạng")
    @Size(max = 150, message = "Email thanh toán không được vượt quá 150 ký tự")
    private String billingEmail;

    private CompanyStatus status;
}
