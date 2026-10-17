package fu.se184491.loadmaster_be.service.route;

import fu.se184491.loadmaster_be.dto.response.route.RouteGuidanceStepResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Đổi bước chỉ dẫn Goong (JSON trên {@code RouteLeg.steps}) sang DTO và ngược lại.
 */
public final class RouteGuidanceMapper {

    private RouteGuidanceMapper() {
    }

    public static List<RouteGuidanceStepResponse> toResponses(List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<RouteGuidanceStepResponse> responses = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            if (step == null) {
                continue;
            }
            responses.add(RouteGuidanceStepResponse.builder()
                    .instruction(stringVal(step.get("instruction")))
                    .maneuver(stringVal(step.get("maneuver")))
                    .distanceMeters(intVal(step.get("distanceMeters")))
                    .durationSeconds(intVal(step.get("durationSeconds")))
                    .lat(doubleVal(step.get("lat")))
                    .lng(doubleVal(step.get("lng")))
                    .build());
        }
        return responses;
    }

    public static List<Map<String, Object>> toMaps(List<RouteGuidanceStepResponse> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (RouteGuidanceStepResponse step : steps) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("instruction", step.getInstruction());
            map.put("maneuver", step.getManeuver());
            map.put("distanceMeters", step.getDistanceMeters());
            map.put("durationSeconds", step.getDurationSeconds());
            map.put("lat", step.getLat());
            map.put("lng", step.getLng());
            maps.add(map);
        }
        return maps;
    }

    private static String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intVal(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double doubleVal(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
