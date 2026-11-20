package fu.se184491.loadmaster_be.service.assistant.extract;

import fu.se184491.loadmaster_be.dto.response.assistant.TryExtractResponse;
import fu.se184491.loadmaster_be.dto.response.assistant.TryExtractResponse.Carton;
import fu.se184491.loadmaster_be.dto.response.assistant.TryExtractResponse.Truck;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Kiểm hậu kỳ chữ mô hình trả về cho {@code try-extract}, hàm thuần. Cấu trúc sai schema (không phải JSON, thiếu hay
 * thừa khoá, sai kiểu) là {@code ASSISTANT_UNAVAILABLE}. Số ngoài giới hạn của form FE ({@code try-form.ts}) thành
 * {@code null} — không kẹp về biên, không tự sửa — và {@code missing} được tính lại từ dữ liệu cuối, không tin danh
 * sách của mô hình. Kiểm cả khi mô hình đã tự kiểm.
 */
public final class TryExtractValidator {

    static final int MAX_CARTON_KINDS = 4;
    static final int MAX_TOTAL_QUANTITY = 1000;
    static final int MAX_ASSUMPTIONS = 5;
    static final int MAX_ASSUMPTION_CHARS = 300;

    private static final String[] ROOT_KEYS = {"truck", "cartons", "assumptions", "missing"};
    private static final String[] TRUCK_KEYS = {"lengthCm", "widthCm", "heightCm", "payloadKg"};
    private static final String[] CARTON_KEYS = {"lengthCm", "widthCm", "heightCm", "weightKg", "quantity", "stop"};

    // Giới hạn của form: xe dài 100–1400, rộng 100–260, cao 100–300 cm, tải 100–30000 kg
    private static final Range TRUCK_LENGTH = new Range(100, 1400);
    private static final Range TRUCK_WIDTH = new Range(100, 260);
    private static final Range TRUCK_HEIGHT = new Range(100, 300);
    private static final Range TRUCK_PAYLOAD = new Range(100, 30_000);
    // Kiện: mỗi cạnh 5–300 cm, khối lượng 0,1–1000 kg, số lượng 1–1000, điểm giao 1–4
    private static final Range CARTON_SIDE = new Range(5, 300);
    private static final Range CARTON_WEIGHT = new Range(new BigDecimal("0.1"), BigDecimal.valueOf(1000));
    private static final Range CARTON_QUANTITY = new Range(1, 1000);
    private static final Range CARTON_STOP = new Range(1, 4);

    // Số thập phân đọc thành BigDecimal để kiểm và làm tròn đúng chữ số mô hình viết
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private TryExtractValidator() {
    }

    public static TryExtractResponse validate(String modelText) {
        JsonNode root;
        try {
            root = JSON.readTree(modelText == null ? "" : modelText);
        } catch (JacksonException e) {
            throw unavailable();
        }
        requireObject(root, ROOT_KEYS);

        Set<String> missing = new LinkedHashSet<>();
        Truck truck = truck(root.get("truck"), missing);

        JsonNode cartonNodes = requireArray(root.get("cartons"));
        List<Carton> cartons = new ArrayList<>();
        for (int i = 0; i < cartonNodes.size(); i++) {
            Carton carton = carton(cartonNodes.get(i), i, missing);
            if (i < MAX_CARTON_KINDS) {
                cartons.add(carton);
            }
        }
        // Mô hình có thể đã tự cắt còn 4 loại và báo cartonKinds; mọi đường dẫn khác tính lại từ dữ liệu
        List<String> modelMissing = strings(root.get("missing"));
        if (cartons.isEmpty()) {
            missing.add("cartons");
        }
        if (cartonNodes.size() > MAX_CARTON_KINDS || modelMissing.contains("cartonKinds")) {
            missing.add("cartonKinds");
        }
        int total = cartons.stream().mapToInt(carton -> carton.quantity() == null ? 0 : carton.quantity()).sum();
        if (total > MAX_TOTAL_QUANTITY) {
            missing.add("totalQuantity");
        }

        return new TryExtractResponse(truck, List.copyOf(cartons), assumptions(root.get("assumptions")),
                List.copyOf(missing));
    }

    private static Truck truck(JsonNode node, Set<String> missing) {
        if (node.isNull()) {
            return null;
        }
        requireObject(node, TRUCK_KEYS);
        return new Truck(
                decimal(node, "lengthCm", TRUCK_LENGTH, "truck", missing),
                decimal(node, "widthCm", TRUCK_WIDTH, "truck", missing),
                decimal(node, "heightCm", TRUCK_HEIGHT, "truck", missing),
                decimal(node, "payloadKg", TRUCK_PAYLOAD, "truck", missing));
    }

    /** Kiện thứ 5 trở đi vẫn được kiểm cấu trúc, nhưng không thêm đường dẫn vào {@code missing}. */
    private static Carton carton(JsonNode node, int index, Set<String> missing) {
        requireObject(node, CARTON_KEYS);
        Set<String> sink = index < MAX_CARTON_KINDS ? missing : new LinkedHashSet<>();
        String prefix = "cartons[" + index + "]";
        return new Carton(
                decimal(node, "lengthCm", CARTON_SIDE, prefix, sink),
                decimal(node, "widthCm", CARTON_SIDE, prefix, sink),
                decimal(node, "heightCm", CARTON_SIDE, prefix, sink),
                decimal(node, "weightKg", CARTON_WEIGHT, prefix, sink),
                integer(node, "quantity", CARTON_QUANTITY, prefix, sink),
                integer(node, "stop", CARTON_STOP, prefix, sink));
    }

    /** Kiểm khoảng trên số gốc rồi làm tròn 2 chữ số thập phân (form FE chỉ đọc tối đa 2 chữ số sau dấu thập phân). */
    private static Double decimal(JsonNode parent, String field, Range range, String prefix, Set<String> missing) {
        BigDecimal value = number(parent.get(field));
        if (value == null || !range.contains(value)) {
            missing.add(prefix + "." + field);
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static Integer integer(JsonNode parent, String field, Range range, String prefix, Set<String> missing) {
        BigDecimal value = number(parent.get(field));
        if (value == null || value.stripTrailingZeros().scale() > 0 || !range.contains(value)) {
            missing.add(prefix + "." + field);
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

    private static List<String> assumptions(JsonNode node) {
        List<String> kept = new ArrayList<>();
        for (String sentence : strings(node)) {
            String flat = sentence.replaceAll("\\s+", " ").strip();
            if (!flat.isEmpty() && kept.size() < MAX_ASSUMPTIONS) {
                kept.add(shorten(flat));
            }
        }
        return List.copyOf(kept);
    }

    private static String shorten(String sentence) {
        if (sentence.codePointCount(0, sentence.length()) <= MAX_ASSUMPTION_CHARS) {
            return sentence;
        }
        int end = sentence.offsetByCodePoints(0, MAX_ASSUMPTION_CHARS - 1);
        return sentence.substring(0, end).stripTrailing() + "…";
    }

    private static List<String> strings(JsonNode node) {
        JsonNode array = requireArray(node);
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isString()) {
                throw unavailable();
            }
            values.add(item.stringValue());
        }
        return values;
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

    private record Range(BigDecimal min, BigDecimal max) {
        Range(long min, long max) {
            this(BigDecimal.valueOf(min), BigDecimal.valueOf(max));
        }

        boolean contains(BigDecimal value) {
            return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
        }
    }
}
