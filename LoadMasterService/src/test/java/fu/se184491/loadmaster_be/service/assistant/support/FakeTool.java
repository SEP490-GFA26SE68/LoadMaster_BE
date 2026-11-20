package fu.se184491.loadmaster_be.service.assistant.support;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Công cụ giả: ghi lại tham số mỗi lần được gọi, kết quả do test quyết. */
public final class FakeTool implements ToolCallback {

    private final ToolDefinition definition;
    private final Function<String, String> body;
    private final List<String> calls = new ArrayList<>();

    private FakeTool(String name, Function<String, String> body) {
        this.definition = ToolDefinition.builder().name(name).description("Công cụ giả " + name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
        this.body = body;
    }

    public static FakeTool returning(String name, String result) {
        return new FakeTool(name, arguments -> result);
    }

    public static FakeTool running(String name, Function<String, String> body) {
        return new FakeTool(name, body);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        calls.add(toolInput);
        return body.apply(toolInput);
    }

    public List<String> calls() {
        return calls;
    }
}
