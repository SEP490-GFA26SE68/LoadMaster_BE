package fu.se184491.loadmaster_be.dto.request.planning;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
public class ExtendDeliveryDeadlineRequest {

    @NotNull
    @Future
    private LocalDateTime newDeadline;

    @NotBlank
    @Size(max = 500)
    private String reason;
}
