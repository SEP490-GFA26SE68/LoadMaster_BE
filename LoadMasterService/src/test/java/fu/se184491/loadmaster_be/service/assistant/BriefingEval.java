package fu.se184491.loadmaster_be.service.assistant;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.assistant.AssistantBriefingResponse;
import fu.se184491.loadmaster_be.service.assistant.briefing.BriefingFacts;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantLink;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nhóm D của bộ kiểm thử hành vi (bản tin "Tóm tắt hôm nay"): đọc sự kiện của ca, so bản tin với kỳ vọng, ghi báo cáo.
 * Tách khỏi {@link AssistantEvalTest} để lớp đó chỉ thêm một chế độ chạy.
 */
final class BriefingEval {

    record Outcome(JsonNode testCase, AssistantBriefingResponse briefing, long tokens, List<String> failures) {
        boolean passed() {
            return failures.isEmpty();
        }
    }

    private BriefingEval() {
    }

    /** Sự kiện của ca, đúng dạng bộ đếm của máy chủ trả. */
    static BriefingFacts factsOf(JsonNode facts) {
        Map<TripStatus, Long> today = new EnumMap<>(TripStatus.class);
        JsonNode byStatus = facts.get("tripsToday");
        if (byStatus != null) {
            for (Map.Entry<String, JsonNode> entry : byStatus.properties()) {
                today.put(TripStatus.valueOf(entry.getKey()), entry.getValue().asLong());
            }
        }
        List<BriefingFacts.AttentionTrip> trips = new ArrayList<>();
        JsonNode attention = facts.get("attentionTrips");
        if (attention != null) {
            for (JsonNode trip : attention) {
                trips.add(new BriefingFacts.AttentionTrip(trip.get("tripCode").asString(),
                        number(trip, "escalatedExceptions"), number(trip, "openExceptions"),
                        number(trip, "lateRiskStops"), number(trip, "pendingManualConfirmations"),
                        trip.path("awaitingApproval").asBoolean(), trip.path("stalePlan").asBoolean()));
            }
        }
        return new BriefingFacts(today, number(facts, "lateRiskStops"), number(facts, "openExceptions"),
                number(facts, "escalatedExceptions"), number(facts, "pendingManualConfirmations"),
                optional(facts, "tripsAwaitingApproval"), optional(facts, "tripsWithStalePlan"),
                optional(facts, "creditsBalance"), optional(facts, "lowCreditThreshold"), trips);
    }

    static void check(JsonNode testCase, AssistantBriefingResponse briefing, long tokens, List<String> failures) {
        List<String> bullets = briefing.bullets();
        String all = fold(String.join(" | ", bullets));
        JsonNode min = testCase.get("minBullets");
        if (min != null && bullets.size() < min.asInt()) {
            failures.add("chỉ còn " + bullets.size() + " gạch đầu dòng sau khi lọc số, cần " + min.asInt());
        }
        if (bullets.size() > 6) {
            failures.add(bullets.size() + " gạch đầu dòng");
        }
        List<String> exact = list(testCase, "exactBullets");
        if (!exact.isEmpty() && !exact.equals(bullets)) {
            failures.add("bullets = " + bullets);
        }
        List<String> first = list(testCase, "firstBulletMustContainAny");
        if (!first.isEmpty() && (bullets.isEmpty()
                || first.stream().noneMatch(piece -> fold(bullets.getFirst()).contains(fold(piece))))) {
            failures.add("gạch đầu tiên không nói việc gấp nhất " + first);
        }
        for (String piece : list(testCase, "mustContain")) {
            if (!all.contains(fold(piece))) {
                failures.add("thiếu \"" + piece + "\"");
            }
        }
        List<String> anyPiece = list(testCase, "mustContainAny");
        if (!anyPiece.isEmpty() && anyPiece.stream().noneMatch(piece -> all.contains(fold(piece)))) {
            failures.add("thiếu mọi chữ trong " + anyPiece);
        }
        for (String piece : list(testCase, "mustNotContain")) {
            if (all.contains(fold(piece))) {
                failures.add("có \"" + piece + "\"");
            }
        }
        List<String> links = briefing.links().stream().map(AssistantLink::to).toList();
        for (String path : list(testCase, "linksMustInclude")) {
            if (!links.contains(path)) {
                failures.add("thiếu liên kết " + path);
            }
        }
        JsonNode english = testCase.get("bulletsInEnglish");
        if (english != null && english.asBoolean() && hasVietnameseMarks(all)) {
            failures.add("bản tin không viết bằng tiếng Anh");
        }
        JsonNode modelCall = testCase.get("expectModelCall");
        if (modelCall != null && !modelCall.asBoolean() && tokens > 0) {
            failures.add("đã gọi mô hình (" + tokens + " token)");
        }
    }

    static void writeReport(Path report, List<Outcome> outcomes, String summary) throws IOException {
        StringBuilder out = new StringBuilder("# Bộ kiểm thử hành vi Lumo — nhóm D (bản tin hôm nay)\n\n")
                .append(summary).append("\n");
        for (Outcome outcome : outcomes) {
            JsonNode testCase = outcome.testCase();
            out.append("\n## ").append(outcome.passed() ? "ĐẠT" : "KHÔNG ĐẠT").append(" · ").append(text(testCase, "id"))
                    .append(" · ").append(text(testCase, "role")).append(" · ").append(text(testCase, "locale"))
                    .append(" · token: ").append(outcome.tokens()).append('\n');
            if (!outcome.passed()) {
                out.append("\nLý do: ").append(String.join("; ", outcome.failures())).append('\n');
            }
            if (outcome.briefing() != null) {
                outcome.briefing().bullets().forEach(bullet -> out.append("\n- ").append(bullet));
                out.append("\n\nLiên kết: ")
                        .append(outcome.briefing().links().stream().map(AssistantLink::to).toList()).append('\n');
            }
        }
        Files.createDirectories(report.getParent());
        Files.writeString(report, out, StandardCharsets.UTF_8);
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? null : value.asString();
    }

    private static long number(JsonNode node, String field) {
        return node.path(field).asLong();
    }

    private static Long optional(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static List<String> list(JsonNode node, String field) {
        List<String> items = new ArrayList<>();
        JsonNode value = node.get(field);
        if (value != null) {
            value.forEach(item -> items.add(item.asString()));
        }
        return items;
    }

    private static String fold(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static boolean hasVietnameseMarks(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return decomposed.chars().anyMatch(c -> (c >= 0x0300 && c <= 0x036F) || c == 'đ' || c == 'Đ');
    }
}
