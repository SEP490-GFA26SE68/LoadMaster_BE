package fu.se184491.loadmaster_be.repository.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.dto.response.optimization.PlanScoreComponentResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlanScoreResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Đọc hai cột JSON do dịch vụ tối ưu ghi (V57): điểm J của phương án ({@code load_plans.plan_score}, khoá snake_case) và
 * lý do đặt của chỗ xếp ({@code package_placements.reason_codes}). Cột trống, JSON hỏng hay sai dạng thì trả null — không bịa
 * điểm hay lý do nào.
 */
final class PlanScoreJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PlanScoreJson() {
    }

    static PlanScoreResponse planScore(String raw) {
        JsonNode node = parse(raw);
        if (node == null || !node.isObject()) {
            return null;
        }
        List<PlanScoreComponentResponse> components = new ArrayList<>();
        JsonNode items = node.get("components");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                if (item != null && item.isObject()) {
                    components.add(new PlanScoreComponentResponse(text(item, "code"), text(item, "name"),
                            number(item, "value"), number(item, "weight"), number(item, "contribution"),
                            bool(item, "computed")));
                }
            }
        }
        return new PlanScoreResponse(text(node, "profile"), text(node, "profile_name"), bool(node, "assumed_weights"),
                number(node, "j"), number(node, "u_vol"), number(node, "u_n"), number(node, "w_vol"), number(node, "w_n"),
                List.copyOf(components));
    }

    /** Mã lý do đặt theo thứ tự đã lưu; cột trống là null (khác danh sách rỗng: phương án không có lý do). */
    static List<String> reasonCodes(String raw) {
        JsonNode node = parse(raw);
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> codes = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                codes.add(item.asText().trim());
            }
        }
        return List.copyOf(codes);
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : null;
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }
}
