package fu.se184491.loadmaster_be.dto.response.monitoring;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RerouteProposalResponse {

    private String tripId;
    /** ISO-8601 */
    private String requestedAt;
    private String stopId;
    private Integer stopNumber;
    private List<RerouteOptionResponse> options;
    private Boolean isMockResult;
}
