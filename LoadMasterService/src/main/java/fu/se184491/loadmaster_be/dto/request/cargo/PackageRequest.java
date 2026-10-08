package fu.se184491.loadmaster_be.dto.request.cargo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageRequest {

    @NotNull(message = "Order ID is required")
    private Long orderId;

    @NotNull(message = "Package Type ID is required")
    private Long packageTypeId;

    @NotBlank(message = "Tracking barcode is required")
    private String trackingBarcode;

    @NotNull(message = "Actual length is required")
    @Positive(message = "Actual length must be greater than 0")
    private Integer actualLength;

    @NotNull(message = "Actual weight is required")
    @Positive(message = "Actual weight must be greater than 0")
    private BigDecimal actualWeightKg;

    private Boolean isPinned;
}
