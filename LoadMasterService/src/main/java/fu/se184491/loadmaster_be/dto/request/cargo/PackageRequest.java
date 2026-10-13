package fu.se184491.loadmaster_be.dto.request.cargo;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
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

    /** Optional: if null, package is stored in warehouse unassigned to an order. */
    private Long orderId;

    /** Optional package type. */
    private Long packageTypeId;

    @NotBlank(message = "Tracking barcode is required")
    private String trackingBarcode;

    private String packageCode;

    @NotNull(message = "Actual length is required")
    @Positive(message = "Actual length must be greater than 0")
    private Integer actualLength;

    @Positive(message = "Actual width must be greater than 0")
    private Integer actualWidth;

    @Positive(message = "Actual height must be greater than 0")
    private Integer actualHeight;

    @NotNull(message = "Actual weight is required")
    @Positive(message = "Actual weight must be greater than 0")
    private BigDecimal actualWeightKg;

    private HandlingClass handlingClass;

    private String destination;

    private Boolean isPinned;
}
