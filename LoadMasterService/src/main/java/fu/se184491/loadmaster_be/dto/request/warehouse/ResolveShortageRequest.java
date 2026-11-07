package fu.se184491.loadmaster_be.dto.request.warehouse;

import fu.se184491.loadmaster_be.constant.warehouse.ShortageDecision;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Điều phối viên quyết một kiện kho báo thiếu. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResolveShortageRequest {

    @NotNull(message = "Quyết định không được để trống")
    private ShortageDecision decision;
}
