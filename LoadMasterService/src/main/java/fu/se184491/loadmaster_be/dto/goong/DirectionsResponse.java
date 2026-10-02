package fu.se184491.loadmaster_be.dto.goong;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DirectionsResponse {
    private List<Route> routes;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Route {
        private List<Leg> legs;

        @JsonProperty("overview_polyline")
        private OverviewPolyline overviewPolyline;

        public long getDurationSeconds() {
            return legs == null ? 0 : legs.stream()
                    .map(Leg::getDuration)
                    .filter(java.util.Objects::nonNull)
                    .mapToLong(DistanceMatrixResponse.Measure::getValue)
                    .sum();
        }

        public long getDistanceMeters() {
            return legs == null ? 0 : legs.stream()
                    .map(Leg::getDistance)
                    .filter(java.util.Objects::nonNull)
                    .mapToLong(DistanceMatrixResponse.Measure::getValue)
                    .sum();
        }

        public String getPolyline() {
            return overviewPolyline == null ? null : overviewPolyline.getPoints();
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Leg {
        private DistanceMatrixResponse.Measure distance;
        private DistanceMatrixResponse.Measure duration;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OverviewPolyline {
        private String points;
    }
}
