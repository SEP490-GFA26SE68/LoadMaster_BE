package fu.se184491.loadmaster_be.dto.request.warehouse;

import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** QR checks a printed QR/barcode; CODE checks Package Code. The entered code is required in both modes. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StagePackageRequest {

    private StagingMethod method;
    private String code;
}
