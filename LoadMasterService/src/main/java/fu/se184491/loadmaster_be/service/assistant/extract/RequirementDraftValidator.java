package fu.se184491.loadmaster_be.service.assistant.extract;

import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import fu.se184491.loadmaster_be.dto.response.assistant.RequirementDraftResponse;
import fu.se184491.loadmaster_be.dto.response.assistant.RequirementDraftResponse.DraftPackage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Kiểm hậu kỳ chữ mô hình trả về cho {@code requirement-draft}, hàm thuần. Cấu trúc sai schema (không phải JSON, thiếu
 * hay thừa khoá, sai kiểu) là {@code ASSISTANT_UNAVAILABLE}. Giá trị không dùng được thành {@code null} — không sửa,
 * không kẹp về biên: hạn sai dạng hoặc không ở tương lai theo giờ Việt Nam, mức ưu tiên ngoài
 * {@link DeliveryRequirementPriority}, số kiện không phải số nguyên dương. Chữ dài bị cắt theo giới hạn cột của máy chủ.
 * {@code missing} được tính lại từ dữ liệu cuối, không tin danh sách của mô hình.
 */
public final class RequirementDraftValidator {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Như cột {@code destination} và {@code note} của yêu cầu giao, tên khách gửi hàng. */
    static final int MAX_DESTINATION_CHARS = 255;
    static final int MAX_NOTE_CHARS = 500;
    static final int MAX_CUSTOMER_CHARS = 150;
    static final int MAX_PACKAGES = 20;
    static final int MAX_DESCRIPTION_CHARS = 200;
    /** Chặn số vô lý của kích thước (cm) và khối lượng (kg) một kiện. */
    private static final BigDecimal MAX_MEASURE = BigDecimal.valueOf(1_000_000);

    static final String DESTINATION = "destinationText";
    static final String DEADLINE = "deadline";
    static final String PRIORITY = "priority";
    static final String PACKAGE_COUNT = "expectedPackageCount";

    private static final String[] ROOT_KEYS = {DESTINATION, DEADLINE, PRIORITY, PACKAGE_COUNT, "note",
            "customerNameGuess", "packages", "missing"};
    private static final String[] PACKAGE_KEYS = {"description", "quantity", "lengthCm", "widthCm", "heightCm",
            "weightKg"};

    private static final Pattern DEADLINE_SHAPE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}$");
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

    // Số thập phân đọc thành BigDecimal để kiểm và làm tròn đúng chữ số mô hình viết
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private RequirementDraftValidator() {
    }

    /**
     * @param now giờ hiện tại theo giờ Việt Nam; hạn phải sau mốc này
     */
    public static RequirementDraftResponse validate(String modelText, LocalDateTime now) {
        JsonNode root;
        try {
            root = JSON.readTree(modelText == null ? "" : modelText);
        } catch (JacksonException e) {
            throw unavailable();
        }
        requireObject(root, ROOT_KEYS);
        // missing của mô hình chỉ được kiểm cấu trúc
        requireStrings(root.get("missing"));

        String destination = oneLine(text(root.get(DESTINATION)), MAX_DESTINATION_CHARS);
        String deadline = deadline(text(root.get(DEADLINE)), now);
        DeliveryRequirementPriority priority = priority(text(root.get(PRIORITY)));
        Integer packageCount = positiveInteger(root.get(PACKAGE_COUNT));
        String note = cut(paragraph(text(root.get("note"))), MAX_NOTE_CHARS);
        String customer = oneLine(text(root.get("customerNameGuess")), MAX_CUSTOMER_CHARS);
        List<DraftPackage> packages = packages(root.get("packages"));

        List<String> missing = new ArrayList<>();
        if (destination == null) {
            missing.add(DESTINATION);
        }
        if (deadline == null) {
            missing.add(DEADLINE);
        }
        if (priority == null) {
            missing.add(PRIORITY);
        }
        if (packageCount == null) {
            missing.add(PACKAGE_COUNT);
        }
        return new RequirementDraftResponse(destination, deadline, priority, packageCount, note, customer, packages,
                List.copyOf(missing));
    }

    /** Đúng dạng, là ngày giờ có thật và ở sau {@code now}; không thì {@code null}. */
    private static String deadline(String value, LocalDateTime now) {
        if (value == null || !DEADLINE_SHAPE.matcher(value).matches()) {
            return null;
        }
        try {
            LocalDateTime at = LocalDateTime.parse(value, DEADLINE_FORMAT);
            return at.isAfter(now) ? DEADLINE_FORMAT.format(at) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static DeliveryRequirementPriority priority(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(DeliveryRequirementPriority.values())
                .filter(level -> level.name().equals(value))
                .findFirst()
                .orElse(null);
    }

    private static List<DraftPackage> packages(JsonNode node) {
        JsonNode array = requireArray(node);
        List<DraftPackage> kept = new ArrayList<>();
        for (JsonNode item : array) {
            requireObject(item, PACKAGE_KEYS);
            String description = oneLine(text(item.get("description")), MAX_DESCRIPTION_CHARS);
            if (description == null || kept.size() >= MAX_PACKAGES) {
                continue;
            }
            kept.add(new DraftPackage(description, positiveInteger(item.get("quantity")),
                    measure(item.get("lengthCm")), measure(item.get("widthCm")), measure(item.get("heightCm")),
                    measure(item.get("weightKg"))));
        }
        return List.copyOf(kept);
    }

    /** Số dương, làm tròn 2 chữ số thập phân; không dương hoặc quá lớn là {@code null}. */
    private static Double measure(JsonNode node) {
        BigDecimal value = number(node);
        if (value == null || value.compareTo(MAX_MEASURE) > 0) {
            return null;
        }
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        return rounded.signum() > 0 ? rounded.doubleValue() : null;
    }

    private static Integer positiveInteger(JsonNode node) {
        BigDecimal value = number(node);
        if (value == null || value.stripTrailingZeros().scale() > 0 || value.signum() <= 0
                || value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return null;
        }
        return value.intValueExact();
    }

    /** {@code null} khi trường là null; trường không phải số là sai schema. */
    private static BigDecimal number(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw unavailable();
        }
        return node.decimalValue();
    }

    /** {@code null} khi trường là null; trường không phải chuỗi là sai schema. */
    private static String text(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw unavailable();
        }
        return node.stringValue();
    }

    /** Gộp mọi khoảng trắng (kể cả xuống dòng) thành một dấu cách; rỗng là {@code null}. */
    private static String oneLine(String value, int max) {
        return value == null ? null : cut(value.replaceAll("\\s+", " "), max);
    }

    /** Giữ xuống dòng, gộp khoảng trắng trong dòng và bỏ dòng trống thừa. */
    private static String paragraph(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n");
    }

    /** Cắt theo ký tự Unicode, bỏ khoảng trắng hai đầu; rỗng là {@code null}. */
    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > max) {
            trimmed = trimmed.substring(0, trimmed.offsetByCodePoints(0, max)).stripTrailing();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void requireStrings(JsonNode node) {
        for (JsonNode item : requireArray(node)) {
            if (!item.isString()) {
                throw unavailable();
            }
        }
    }

    private static JsonNode requireArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw unavailable();
        }
        return node;
    }

    /** Đúng các khoá của schema: thiếu hay thừa khoá đều là sai schema. */
    private static void requireObject(JsonNode node, String[] keys) {
        if (node == null || !node.isObject() || node.size() != keys.length) {
            throw unavailable();
        }
        for (String key : keys) {
            if (node.get(key) == null) {
                throw unavailable();
            }
        }
    }

    private static AppException unavailable() {
        return new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
    }
}
