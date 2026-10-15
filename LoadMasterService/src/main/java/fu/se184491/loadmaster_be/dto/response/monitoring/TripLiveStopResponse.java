package fu.se184491.loadmaster_be.dto.response.monitoring;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TripLiveStopResponse {

    private String stopId;
    private Integer number;
    /** ISO-8601 estimated arrival */
    private String eta;
    /** ISO-8601 deadline proxy (planned_arrival) */
    private String deadline;
    private String deadlineStatus;
    private Boolean arrived;
}
