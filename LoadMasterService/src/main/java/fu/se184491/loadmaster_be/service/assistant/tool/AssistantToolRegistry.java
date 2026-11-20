package fu.se184491.loadmaster_be.service.assistant.tool;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.entity.assistant.AssistantToolCall;
import fu.se184491.loadmaster_be.repository.assistant.AssistantToolCallRepository;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Sổ công cụ của trợ lý. Lúc khởi động đọc mọi {@code @Tool} của các bean {@link AssistantTool} (tên trùng, tên sai
 * dạng, phương thức không public, bean không ai dùng được là lỗi khởi động). Mỗi lượt, {@link #toolsFor} chỉ trả công cụ
 * trong phạm vi người hỏi, bọc lớp ghi lại từng lần gọi vào {@link ToolCallLog}; {@link #save} lưu nhật ký đó cùng tin
 * trợ lý. Không bao giờ lưu kết quả công cụ.
 */
@Component
public class AssistantToolRegistry {

    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,59}$");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {
    };

    private final List<Registered> tools;
    private final AssistantToolCallRepository calls;
    private final Clock clock;

    @Autowired
    public AssistantToolRegistry(ObjectProvider<AssistantTool> beans, AssistantToolCallRepository calls) {
        this(beans.orderedStream().toList(), calls, Clock.systemUTC());
    }

    public AssistantToolRegistry(List<AssistantTool> beans, AssistantToolCallRepository calls, Clock clock) {
        this.tools = register(beans);
        this.calls = calls;
        this.clock = clock;
    }

    /** Công cụ người hỏi được dùng trong lượt này: khách chỉ có công cụ khai {@code guest()}; người đăng nhập có hợp các vai trò. */
    public List<ToolCallback> toolsFor(ToolScope scope, ToolCallLog log) {
        List<ToolCallback> out = new ArrayList<>();
        for (Registered tool : tools) {
            boolean allowed = scope.guest() ? tool.guest() : !Collections.disjoint(tool.roles(), scope.roles());
            if (allowed) {
                out.add(new Recording(tool.callback(), log, clock));
            }
        }
        return out;
    }

    /** Lưu các lần gọi của một lượt, gắn với tin trợ lý của lượt đó. Gọi trong giao dịch lưu tin. */
    public void save(ToolCallLog log, Long messageId) {
        if (log.entries().isEmpty()) {
            return;
        }
        calls.saveAll(log.entries().stream()
                .map(entry -> AssistantToolCall.builder()
                        .messageId(messageId).toolName(entry.toolName()).arguments(entry.arguments())
                        .ok(entry.ok()).durationMs(entry.durationMs()).createdAt(entry.at())
                        .build())
                .toList());
    }

    private static List<Registered> register(List<AssistantTool> beans) {
        List<Registered> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (AssistantTool bean : beans) {
            Class<?> type = AopUtils.getTargetClass(bean);
            Set<UserRole> roles = bean.roles() == null ? Set.of() : Set.copyOf(bean.roles());
            if (roles.isEmpty() && !bean.guest()) {
                throw new IllegalStateException("Bean công cụ " + type.getSimpleName() + " không khai vai trò nào");
            }
            for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type,
                    candidate -> candidate.isAnnotationPresent(Tool.class))) {
                if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                    throw new IllegalStateException("Công cụ " + type.getSimpleName() + "." + method.getName()
                            + " phải là phương thức public, không static");
                }
                ToolDefinition definition = ToolDefinitions.from(method);
                String name = definition.name();
                if (!TOOL_NAME.matcher(name).matches()) {
                    throw new IllegalStateException("Tên công cụ phải là snake_case, tối đa 60 ký tự: " + name);
                }
                if (!names.add(name)) {
                    throw new IllegalStateException("Trùng tên công cụ: " + name);
                }
                ToolCallback callback = MethodToolCallback.builder()
                        .toolDefinition(definition)
                        .toolMethod(AopUtils.selectInvocableMethod(method, bean.getClass()))
                        .toolObject(bean)
                        .toolCallResultConverter(ToolResults.CONVERTER)
                        .build();
                out.add(new Registered(callback, roles, bean.guest()));
            }
        }
        return List.copyOf(out);
    }

    private record Registered(ToolCallback callback, Set<UserRole> roles, boolean guest) {
    }

    /** Ghi tên, tham số, đạt hay không, thời gian của mỗi lần gọi; ngoại lệ vẫn ném tiếp cho vòng gọi xử lý. */
    private record Recording(ToolCallback delegate, ToolCallLog log, Clock clock) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String input) {
            return record(input, () -> delegate.call(input));
        }

        @Override
        public String call(String input, ToolContext context) {
            return record(input, () -> delegate.call(input, context));
        }

        private String record(String input, java.util.function.Supplier<String> run) {
            Instant at = clock.instant();
            long start = System.nanoTime();
            boolean ok = false;
            try {
                String out = run.get();
                ok = out == null || !out.startsWith("{\"error\":");
                return out;
            } finally {
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                log.add(new ToolCallLog.Entry(delegate.getToolDefinition().name(), arguments(input), ok,
                        (int) Math.min(Integer.MAX_VALUE, millis), at));
            }
        }

        private static Map<String, Object> arguments(String input) {
            if (input == null || input.isBlank()) {
                return Map.of();
            }
            try {
                return JSON.readValue(input, ARGUMENTS);
            } catch (JacksonException e) {
                return null;
            }
        }
    }
}
