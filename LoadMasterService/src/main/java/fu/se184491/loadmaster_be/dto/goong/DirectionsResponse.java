package fu.se184491.loadmaster_be.dto.goong;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        /** Bước chỉ dẫn rút gọn để lưu JSON trên {@code RouteLeg.steps}. */
        public List<Map<String, Object>> toGuidanceSteps() {
            if (legs == null || legs.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> steps = new ArrayList<>();
            for (Leg leg : legs) {
                if (leg.getSteps() == null) {
                    continue;
                }
                for (Step step : leg.getSteps()) {
                    Map<String, Object> map = step.toGuidanceMap();
                    if (map != null) {
                        steps.add(map);
                    }
                }
            }
            return steps;
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
        private List<Step> steps;

        public Leg(DistanceMatrixResponse.Measure distance, DistanceMatrixResponse.Measure duration) {
            this(distance, duration, null);
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Step {
        private DistanceMatrixResponse.Measure distance;
        private DistanceMatrixResponse.Measure duration;
        @JsonProperty("html_instructions")
        private String htmlInstructions;
        private String maneuver;
        @JsonProperty("start_location")
        private Location startLocation;
        private OverviewPolyline polyline;

        Map<String, Object> toGuidanceMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("instruction", plainInstruction());
            map.put("maneuver", maneuver == null || maneuver.isBlank() ? "straight" : maneuver);
            map.put("distanceMeters", distance == null ? 0 : Math.toIntExact(distance.getValue()));
            map.put("durationSeconds", duration == null ? 0 : Math.toIntExact(duration.getValue()));
            if (startLocation != null) {
                map.put("lat", startLocation.getLat());
                map.put("lng", startLocation.getLng());
            }
            return map;
        }

        private String plainInstruction() {
            if (htmlInstructions == null || htmlInstructions.isBlank()) {
                return maneuver == null ? "" : maneuver;
            }
            return htmlInstructions.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Location {
        private Double lat;
        private Double lng;
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
