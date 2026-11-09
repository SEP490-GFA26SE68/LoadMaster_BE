package fu.se184491.loadmaster_be.dto.response.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Số seal đã ghi cho phiên xếp của chuyến. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SealResponse {

    private Long tripId;
    private Long executionId;
    private String sealNumber;
    private LocalDateTime sealedAt;
}
