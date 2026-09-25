package fu.se184491.loadmaster_be.dto.response.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompleteLoadingResponse {

    private Long executionId;
    private Long tripId;
    private String tripCode;
    private String status;
    private String tripStatus;
    private Integer totalPackages;
    private Integer totalDeviations;
    private LocalDateTime completedAt;
}
