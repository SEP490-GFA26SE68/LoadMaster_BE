package fu.se184491.loadmaster_be.dto.response.monitoring;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EtaRiskAlertResponse {

    private String eventId;
    /** ISO-8601 */
    private String at;
    private String tripId;
    private String stopId;
    private Integer stopNumber;
    private String status;
    /** ISO-8601 */
    private String eta;
    /** ISO-8601 */
    private String deadline;
}
