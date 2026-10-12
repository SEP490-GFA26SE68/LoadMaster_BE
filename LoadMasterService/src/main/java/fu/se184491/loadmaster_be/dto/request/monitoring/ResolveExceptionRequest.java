package fu.se184491.loadmaster_be.dto.request.monitoring;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResolveExceptionRequest {

    @Size(max = 300, message = "Ghi chú tối đa 300 ký tự")
    private String note;
}
