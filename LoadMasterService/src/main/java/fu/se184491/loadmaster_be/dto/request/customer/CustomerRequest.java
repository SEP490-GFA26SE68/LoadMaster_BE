
package fu.se184491.loadmaster_be.dto.request.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerRequest {

    @NotBlank
    @Size(max = 150)
    private String name;

    @NotBlank
    @Size(max = 20)
    private String contactPhone;

    @NotBlank
    @Size(max = 255)
    private String address;
}
