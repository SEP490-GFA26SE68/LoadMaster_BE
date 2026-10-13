package fu.se184491.loadmaster_be.dto.response.monitoring;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitoringBoardResponse {

    private List<MonitoringTripResponse> trips;
    private Map<String, String> userNames;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonitoringTripResponse {
        private String tripId;
        private String name;
        private String vehicleName;
        private String driverName;
        private DepotResponse depot;
        private List<MonitoringStopResponse> stops;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepotResponse {
        private String name;
        private Double lat;
        private Double lng;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MonitoringStopResponse {
        private String id;
        private Integer number;
        private String name;
        private Double lat;
        private Double lng;
        private String arrivedAt;
        private String completedAt;
    }
}
