package fu.se184491.loadmaster_be.service.assistant.tool;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.entity.assistant.AssistantToolCall;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.assistant.AssistantToolCallRepository;
import fu.se184491.loadmaster_be.service.assistant.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AssistantToolRegistryTest {

    private static final Instant T0 = Instant.parse("2026-11-20T03:00:00Z");

    public static class DispatcherTools implements AssistantTool {
        @Override
        public Set<UserRole> roles() {
            return Set.of(UserRole.DISPATCHER);
        }

        @Tool(name = "get_trip", description = "Một chuyến của công ty")
        public Map<String, Object> getTrip(@ToolParam(description = "Mã chuyến") String tripCode) {
            if ("TRIP-404".equals(tripCode)) {
                throw new AppException(ErrorCode.TRIP_NOT_FOUND);
            }
            return Map.of("tripCode", tripCode, "status", "PLANNED");
        }

        @Tool(name = "raw_json", description = "Trả chuỗi JSON dựng sẵn")
        public String rawJson() {
            return "{\"ok\":true}";
        }

        @Tool(name = "refuse", description = "Trả mã lỗi dựng sẵn")
        public String refuse() {
            return ToolResults.error("TRIP_LOCKED");
        }
    }

    public static class DriverTools implements AssistantTool {
        @Override
        public Set<UserRole> roles() {
            return Set.of(UserRole.DRIVER);
        }

        @Tool(name = "my_trips", description = "Chuyến của tài xế")
        public List<String> myTrips() {
            return List.of("TRIP-1");
        }
    }

    public static class PublicTools implements AssistantTool {
        @Override
        public Set<UserRole> roles() {
            return Set.of();
        }

        @Override
        public boolean guest() {
            return true;
        }

        @Tool(name = "pricing_plans", description = "Bảng giá công khai")
        public List<String> pricingPlans() {
            return List.of("BASIC");
        }
    }

    public static class DuplicateTools implements AssistantTool {
        @Override
        public Set<UserRole> roles() {
            return Set.of(UserRole.MANAGER);
        }

        @Tool(name = "get_trip", description = "Trùng tên")
        public String other() {
            return "{}";
        }
    }

    public static class CamelCaseTools implements AssistantTool {
        @Override
        public Set<UserRole> roles() {
            return Set.of(UserRole.MANAGER);
        }

        @Tool(name = "getTrip", description = "Sai dạng tên")
        public String getTrip() {
            return "{}";
        }
    }

    public static class NobodyTools implements AssistantTool {
        @Override
        public Set<UserRole> roles() {
            return Set.of();
        }

        @Tool(name = "nobody", description = "Không ai dùng được")
        public String nobody() {
            return "{}";
        }
    }

    private final AssistantToolCallRepository repository = mock(AssistantToolCallRepository.class);
    private final MutableClock clock = new MutableClock(T0);

    private AssistantToolRegistry registry(AssistantTool... beans) {
        return new AssistantToolRegistry(Arrays.asList(beans), repository, clock);
    }

    private AssistantToolRegistry standard() {
        return registry(new DispatcherTools(), new DriverTools(), new PublicTools());
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(tool -> tool.getToolDefinition().name()).sorted().toList();
    }

    private static ToolCallback named(List<ToolCallback> tools, String name) {
        return tools.stream().filter(tool -> tool.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void scopeOnlyGetsToolsOfItsRolesAndGuestsOnlyGetGuestTools() {
        AssistantToolRegistry registry = standard();

        assertThat(names(registry.toolsFor(ToolScope.of(UserRole.DRIVER), new ToolCallLog())))
                .containsExactly("my_trips");
        assertThat(names(registry.toolsFor(ToolScope.of(UserRole.DISPATCHER), new ToolCallLog())))
                .containsExactly("get_trip", "raw_json", "refuse");
        assertThat(names(registry.toolsFor(ToolScope.ofGuest(), new ToolCallLog())))
                .containsExactly("pricing_plans");
        assertThat(names(registry.toolsFor(ToolScope.of(UserRole.MANAGER), new ToolCallLog()))).isEmpty();
    }

    @Test
    void twoRolesGetTheUnionOfBoth() {
        assertThat(names(standard().toolsFor(ToolScope.of(UserRole.DISPATCHER, UserRole.DRIVER), new ToolCallLog())))
                .containsExactly("get_trip", "my_trips", "raw_json", "refuse");
    }

    @Test
    void oneCallIsOneRowWithNameArgumentsOkAndDurationButNoResult() {
        ToolCallLog log = new ToolCallLog();
        ToolCallback tool = named(standard().toolsFor(ToolScope.of(UserRole.DISPATCHER), log), "get_trip");

        String result = tool.call("{\"tripCode\":\"TRIP-1\"}");

        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readValue(result, Map.class))
                .isEqualTo(Map.of("tripCode", "TRIP-1", "status", "PLANNED"));
        assertThat(log.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.toolName()).isEqualTo("get_trip");
            assertThat(entry.arguments()).isEqualTo(Map.of("tripCode", "TRIP-1"));
            assertThat(entry.ok()).isTrue();
            assertThat(entry.durationMs()).isNotNegative();
            assertThat(entry.at()).isEqualTo(T0);
        });

        standard().save(log, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AssistantToolCall>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getMessageId()).isEqualTo(42L);
            assertThat(row.getToolName()).isEqualTo("get_trip");
            assertThat(row.getArguments()).isEqualTo(Map.of("tripCode", "TRIP-1"));
            assertThat(row.isOk()).isTrue();
            assertThat(row.getCreatedAt()).isEqualTo(T0);
        });
        assertThat(Arrays.stream(AssistantToolCall.class.getDeclaredFields()).map(Field::getName))
                .containsExactlyInAnyOrder("id", "messageId", "toolName", "arguments", "ok", "durationMs",
                        "createdAt");
    }

    @Test
    void failedCallIsRecordedAsNotOkAndTheExceptionStillReachesTheLoop() {
        ToolCallLog log = new ToolCallLog();
        List<ToolCallback> tools = standard().toolsFor(ToolScope.of(UserRole.DISPATCHER), log);

        assertThatThrownBy(() -> named(tools, "get_trip").call("{\"tripCode\":\"TRIP-404\"}"))
                .isInstanceOf(RuntimeException.class);
        assertThat(named(tools, "refuse").call("{}")).isEqualTo("{\"error\":\"TRIP_LOCKED\"}");

        assertThat(log.entries()).extracting(ToolCallLog.Entry::ok).containsExactly(false, false);
        assertThat(log.toolNames()).containsExactly("get_trip", "refuse");
    }

    @Test
    void stringResultIsNotEncodedTwice() {
        ToolCallback tool = named(standard().toolsFor(ToolScope.of(UserRole.DISPATCHER), new ToolCallLog()),
                "raw_json");

        assertThat(tool.call("{}")).isEqualTo("{\"ok\":true}");
    }

    @Test
    void argumentsTheModelSentBrokenAreStoredAsNull() {
        ToolCallLog log = new ToolCallLog();
        ToolCallback tool = named(standard().toolsFor(ToolScope.of(UserRole.DRIVER), log), "my_trips");

        tool.call("{}");
        assertThatThrownBy(() -> tool.call("{not json")).isInstanceOf(RuntimeException.class);

        assertThat(log.entries()).extracting(ToolCallLog.Entry::arguments).containsExactly(Map.of(), null);
    }

    @Test
    void turnWithoutToolCallsSavesNothing() {
        standard().save(new ToolCallLog(), 42L);

        verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.anyIterable());
    }

    @Test
    void duplicateOrBadlyNamedToolsAndBeansNobodyCanUseFailAtStartup() {
        assertThatThrownBy(() -> registry(new DispatcherTools(), new DuplicateTools()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("get_trip");
        assertThatThrownBy(() -> registry(new CamelCaseTools()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("getTrip");
        assertThatThrownBy(() -> registry(new NobodyTools()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NobodyTools");
    }
}
