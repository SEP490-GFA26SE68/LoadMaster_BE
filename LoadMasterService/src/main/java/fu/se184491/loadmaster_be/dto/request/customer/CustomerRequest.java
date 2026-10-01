package fu.se184491.loadmaster_be.dto.request.customer;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerRequest {
    @NotBlank(message = "Tên khách hàng không được để trống")
    private String name;

    @NotBlank(message = "Số điện thoại liên hệ không được để trống")
    private String contactPhone;

    @NotBlank(message = "Địa chỉ không được để trống")
    private String address;
}
