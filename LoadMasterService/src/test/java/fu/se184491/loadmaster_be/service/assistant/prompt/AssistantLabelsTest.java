package fu.se184491.loadmaster_be.service.assistant.prompt;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.constant.monitoring.LocationSource;
import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionStatus;
import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionType;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mô hình không bao giờ được đưa mã thô (PENDING, IN_TRANSIT…) cho người dùng: mọi mã công cụ trả về phải có nhãn vi / en
 * ở mục "Nhãn trạng thái" của {@code knowledge-app.md}. Test quét mọi bean {@link AssistantTool}, đi theo kiểu trả về
 * của các phương thức {@code @Tool} (record lồng, danh sách, kiểu generic) và gom mọi enum gặp được; mã nằm trong
 * trường chuỗi thì khai tay ở {@link #STRING_CODES}. Thêm enum vào kết quả công cụ mà quên nhãn là test đỏ.
 */
class AssistantLabelsTest {

    private static final String TOOL_PACKAGE = "fu.se184491.loadmaster_be.service.assistant.tool";

    /**
     * Mã đi trong trường {@code String}: nhóm (tên ghi trong ngoặc ở dòng nhãn) → các mã. Lấy từ enum gốc để thêm mã
     * mới ở enum là test đòi nhãn ngay.
     */
    private static final Map<String, List<String>> STRING_CODES = new LinkedHashMap<>();

    static {
        List<String> requirement = new ArrayList<>(names(DeliveryRequirementStatus.values()));
        requirement.add("PARTIAL");
        STRING_CODES.put("DeliveryRequirementStatus", requirement);
        STRING_CODES.put("TripExceptionType", names(TripExceptionType.values()));
        STRING_CODES.put("TripExceptionStatus", names(TripExceptionStatus.values()));
        STRING_CODES.put("SubscriptionTier", names(SubscriptionTier.values()));
        List<String> plan = new ArrayList<>(names(SubscriptionStatus.values()));
        plan.add("NONE");
        STRING_CODES.put("SubscriptionStatus", plan);
        STRING_CODES.put("UserRole", names(UserRole.values()));
        STRING_CODES.put("LocationSource", names(LocationSource.values()));
    }

    @Test
    void everyCodeToolsReturnHasAVietnameseAndEnglishLabel() throws IOException {
        Map<String, String> lines = labelLines(read("assistant/knowledge-app.md"));
        Map<String, List<String>> codes = new LinkedHashMap<>();
        for (Class<?> type : enumsReturnedByTools()) {
            codes.put(type.getSimpleName(), names(type.getEnumConstants()));
        }
        STRING_CODES.forEach((group, values) -> codes.merge(group, values, (left, right) -> {
            Set<String> all = new LinkedHashSet<>(left);
            all.addAll(right);
            return List.copyOf(all);
        }));

        Set<String> missing = new TreeSet<>();
        codes.forEach((group, values) -> {
            String line = lines.get(group);
            for (String code : values) {
                if (line == null || !line.matches(".*\\b" + code + " = [^/;]+ / [^;]+.*")) {
                    missing.add(group + "." + code);
                }
            }
        });
        assertThat(missing).as("mã thiếu nhãn \"MÃ = nhãn vi / nhãn en\" ở knowledge-app.md").isEmpty();
        // Bảo đảm việc quét thật sự tìm thấy enum của công cụ mới.
        assertThat(codes).containsKeys("PackageStatus", "PackageFlag", "VehicleStatus", "TicketStatus",
                "ContactStatus", "TrialRequestStatus", "CompanyStatus", "DeadlineStatus", "TripStatus");
    }

    @Test
    void rulesForbidShowingRawCodes() throws IOException {
        assertThat(read("assistant/rules.md")).contains("Không bao giờ viết mã trạng thái thô");
    }

    private static Map<String, String> labelLines(String knowledge) {
        int start = knowledge.indexOf("## Nhãn trạng thái");
        assertThat(start).as("knowledge-app.md cần mục \"## Nhãn trạng thái\"").isNotNegative();
        Map<String, String> lines = new LinkedHashMap<>();
        for (String line : knowledge.substring(start).split("\n")) {
            int open = line.indexOf('(');
            int close = line.indexOf(')', open + 1);
            if (line.startsWith("- ") && open > 0 && close > open) {
                lines.put(line.substring(open + 1, close).strip(), line.substring(close + 1));
            }
        }
        return lines;
    }

    private static Set<Class<?>> enumsReturnedByTools() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AssistantTool.class));
        Set<Class<?>> enums = new LinkedHashSet<>();
        Set<Type> seen = new LinkedHashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(TOOL_PACKAGE)) {
            Class<?> tool = load(definition.getBeanClassName());
            for (Method method : tool.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    walk(method.getGenericReturnType(), enums, seen);
                }
            }
            for (Class<?> nested : tool.getDeclaredClasses()) {
                walk(nested, enums, seen);
            }
        }
        return enums;
    }

    private static void walk(Type type, Set<Class<?>> enums, Set<Type> seen) {
        if (type == null || !seen.add(type)) {
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            walk(parameterized.getRawType(), enums, seen);
            for (Type argument : parameterized.getActualTypeArguments()) {
                walk(argument, enums, seen);
            }
            return;
        }
        if (type instanceof WildcardType wildcard) {
            Arrays.stream(wildcard.getUpperBounds()).forEach(bound -> walk(bound, enums, seen));
            return;
        }
        if (!(type instanceof Class<?> type1)) {
            return;
        }
        if (type1.isArray()) {
            walk(type1.getComponentType(), enums, seen);
        } else if (type1.isEnum()) {
            if (type1.getName().startsWith("fu.se184491")) {
                enums.add(type1);
            }
        } else if (type1.isRecord()) {
            for (RecordComponent component : type1.getRecordComponents()) {
                walk(component.getGenericType(), enums, seen);
            }
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> names(Object[] constants) {
        return Arrays.stream(constants).map(constant -> ((Enum<?>) constant).name()).toList();
    }

    private static String read(String path) throws IOException {
        try (InputStream in = AssistantLabelsTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
