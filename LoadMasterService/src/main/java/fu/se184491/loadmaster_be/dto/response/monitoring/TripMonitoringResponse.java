package fu.se184491.loadmaster_be.dto.response.monitoring;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripMonitoringResponse {

    private String tripId;
    /** Explicitly included even when null so FE receives location: null */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private LocationPointResponse location;
    private List<TripLiveStopResponse> stops;
    private List<EtaRiskAlertResponse> alerts;
    private List<TripExceptionResponse> exceptions;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private TripRerouteResponse reroute;
    /** Canonical active RoutePlan id (package route). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long activeRoutePlanId;
    /** Polyline geometry from active RoutePlan legs — prefer over Trip.routePlan JSON. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<RouteLegGeometryResponse> routeLegs;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Integer refreshMs;
    private Boolean isMockResult;
}
