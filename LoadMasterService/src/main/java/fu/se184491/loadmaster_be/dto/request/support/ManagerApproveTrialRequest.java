package fu.se184491.loadmaster_be.dto.request.support;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManagerApproveTrialRequest {

    @Size(max = 200, message = "Tên công ty không được vượt quá 200 ký tự")
    private String companyName;

    @Pattern(regexp = "^[A-Za-z0-9_-]{3,50}$", message = "Mã công ty chỉ chứa 3-50 ký tự chữ cái, số, gạch ngang hoặc gạch dưới")
    private String companyCode;

    private String companyAddress;

    @Min(value = 1, message = "Số ngày dùng thử tối thiểu là 1 ngày")
    @Max(value = 365, message = "Số ngày dùng thử tối đa là 365 ngày")
    private Integer trialDays;

    @Min(value = 0, message = "Hạn mức credit không được âm")
    private Integer trialCredits;

    @Size(max = 1000, message = "Ghi chú thẩm định không được vượt quá 1000 ký tự")
    private String managerNote;
}

