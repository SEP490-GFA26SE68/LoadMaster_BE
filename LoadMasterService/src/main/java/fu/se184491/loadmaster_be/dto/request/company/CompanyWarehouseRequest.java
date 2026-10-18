package fu.se184491.loadmaster_be.dto.request.company;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CompanyWarehouseRequest(

        @NotBlank(message = "Tên kho không được để trống")
        @Size(max = 255)
        String warehouseName,

        @NotBlank(message = "Địa chỉ kho không được để trống")
        @Size(max = 500)
        String address,

        @DecimalMin(value = "-90.0")
        @DecimalMax(value = "90.0")
        BigDecimal latitude,

        @DecimalMin(value = "-180.0")
        @DecimalMax(value = "180.0")
        BigDecimal longitude

) {
        @AssertTrue(message = "Vĩ độ và kinh độ phải được nhập cùng nhau")
        public boolean isCoordinatePairValid() {
                return (latitude == null) == (longitude == null);
        }
}
