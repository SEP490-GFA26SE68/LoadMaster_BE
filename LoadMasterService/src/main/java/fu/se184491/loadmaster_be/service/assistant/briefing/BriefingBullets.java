package fu.se184491.loadmaster_be.service.assistant.briefing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lọc gạch đầu dòng mô hình viết: chỉ giữ câu mà mọi số đều có trong sự kiện và mọi mã chuyến đều là chuyến của bản
 * tin. Câu sai bị bỏ cả câu, không sửa.
 */
final class BriefingBullets {

    static final int MAX_BULLETS = 6;
    static final int MAX_CHARS = 300;

    private static final Pattern TRIP_CODE = Pattern.compile("TRIP-[A-Z0-9-]{1,40}");
    // Số có thể kèm dấu ngăn nghìn (1.200 hay 1,200) hoặc phần thập phân (87,4): dạng nào cũng đọc hết một lượt
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern GROUPED = Pattern.compile("\\d{1,3}(?:[.,]\\d{3})+");
    private static final Pattern PLAIN = Pattern.compile("\\d{1,18}");
    private static final Pattern LEADING_MARKER = Pattern.compile("^[-•*·\\s]+");

    private BriefingBullets() {
    }

    static List<String> keep(List<String> bullets, BriefingFacts facts) {
        Set<Long> numbers = new HashSet<>(facts.numbers());
        Set<String> tripCodes = new HashSet<>();
        facts.attentionTrips().forEach(trip -> tripCodes.add(trip.tripCode()));

        Set<String> kept = new LinkedHashSet<>();
        for (String bullet : bullets == null ? List.<String>of() : bullets) {
            String text = clean(bullet);
            if (text != null && grounded(text, numbers, tripCodes)) {
                kept.add(text);
            }
            if (kept.size() == MAX_BULLETS) {
                break;
            }
        }
        return new ArrayList<>(kept);
    }

    private static String clean(String bullet) {
        if (bullet == null) {
            return null;
        }
        String text = LEADING_MARKER.matcher(bullet.replaceAll("\\s+", " ").strip()).replaceFirst("").strip();
        if (text.isEmpty() || text.length() > MAX_CHARS || text.contains("://") || text.contains("www.")) {
            return null;
        }
        return text;
    }

    private static boolean grounded(String text, Set<Long> numbers, Set<String> tripCodes) {
        Matcher codes = TRIP_CODE.matcher(text);
        StringBuilder rest = new StringBuilder();
        while (codes.find()) {
            if (!tripCodes.contains(codes.group())) {
                return false;
            }
            codes.appendReplacement(rest, " ");
        }
        codes.appendTail(rest);

        Matcher found = NUMBER.matcher(rest);
        while (found.find()) {
            Long value = integerOf(found.group());
            if (value == null || !numbers.contains(value)) {
                return false;
            }
        }
        return true;
    }

    /** Số nguyên của một cụm chữ số; {@code null} khi là số thập phân (sự kiện chỉ có số nguyên). */
    private static Long integerOf(String token) {
        if (GROUPED.matcher(token).matches()) {
            String digits = token.replace(".", "").replace(",", "");
            return digits.length() > 18 ? null : Long.parseLong(digits);
        }
        return PLAIN.matcher(token).matches() ? Long.parseLong(token) : null;
    }
}
