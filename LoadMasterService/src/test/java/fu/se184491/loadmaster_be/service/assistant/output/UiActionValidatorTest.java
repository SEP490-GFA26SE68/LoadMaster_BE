package fu.se184491.loadmaster_be.service.assistant.output;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import fu.se184491.loadmaster_be.repository.assistant.AssistantToolCallRepository;
import fu.se184491.loadmaster_be.service.assistant.model.UiAction;
import fu.se184491.loadmaster_be.service.assistant.output.UiActionValidator.ViewerPlan;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantToolRegistry;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolCallLog;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolScope;
import fu.se184491.loadmaster_be.service.assistant.tool.action.LinkTool;
import fu.se184491.loadmaster_be.service.assistant.tool.action.ViewerActionTools;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Whitelist lệnh khung 3D trên phương án dựng tay: 120 kiện xếp ở bước 1…120. */
class UiActionValidatorTest {

    private static final List<String> VIEWER_TOOLS =
            List.of("select_package", "set_camera_preset", "set_step", "set_color_mode", "open_tab");

    private final ViewerPlan plan = ViewerPlan.of(plan(42L, "PK-", 120));

    @Test
    void approveAndAnUnknownPresetAreDroppedWhileValidActionsOfTheSameTurnStay() {
        List<UiAction> asked = List.of(
                action("approvePlan", null, null, null, null, null),
                action("setCameraPreset", null, "bay-len", null, null, null),
                action("setCameraPreset", null, "cua-sau", null, null, null),
                action("selectPackage", "PK-7", null, null, null, null),
                action("setColorMode", null, null, null, "khoi-luong", null));

        assertThat(UiActionValidator.filter(asked, plan)).containsExactly(
                action("setCameraPreset", null, "cua-sau", null, null, null),
                action("selectPackage", "PK-7", null, null, null, null),
                action("setColorMode", null, null, null, "khoi-luong", null));
    }

    @Test
    void stepBeyondThePlanAndAPackageOfAnotherPlanAreDropped() {
        List<UiAction> asked = List.of(
                action("setStep", null, null, 9999, null, null),
                action("setStep", null, null, -1, null, null),
                action("selectPackage", "OTHER-3", null, null, null, null),
                action("setStep", null, null, 120, null, null),
                action("setStep", null, null, 0, null, null));

        assertThat(UiActionValidator.filter(asked, plan)).containsExactly(
                action("setStep", null, null, 120, null, null),
                action("setStep", null, null, 0, null, null));
        assertThat(UiActionValidator.check(asked.getFirst(), plan).reason())
                .isEqualTo(UiActionValidator.STEP_OUT_OF_RANGE);
        assertThat(UiActionValidator.check(asked.get(2), plan).reason())
                .isEqualTo(UiActionValidator.PACKAGE_NOT_IN_PLAN);
    }

    @Test
    void cleanActionKeepsOnlyTheFieldsOfItsTypeAndTheCodeOfThePlan() {
        UiAction messy = action("selectPackage", "pk-9", "tren", 3, "diem-giao", "placed");

        assertThat(UiActionValidator.filter(List.of(messy), plan))
                .containsExactly(action("selectPackage", "PK-9", null, null, null, null));
        assertThat(UiActionValidator.toMap(action("setStep", null, null, 4, null, null)))
                .isEqualTo(Map.of("type", "setStep", "step", 4));
    }

    @Test
    void atMostFiveDistinctActionsAndNothingWithoutAPlan() {
        List<UiAction> asked = new ArrayList<>();
        asked.add(action("openTab", null, null, null, null, "unplaced"));
        asked.add(action("openTab", null, null, null, null, "unplaced"));
        for (int step = 1; step <= 6; step++) {
            asked.add(action("setStep", null, null, step, null, null));
        }

        assertThat(UiActionValidator.filter(asked, plan)).hasSize(UiActionValidator.MAX_ACTIONS)
                .first().isEqualTo(action("openTab", null, null, null, null, "unplaced"));
        assertThat(UiActionValidator.filter(asked, null)).isEmpty();
        assertThat(UiActionValidator.check(action("openTab", null, null, null, null, "editor"), plan).reason())
                .isEqualTo(UiActionValidator.TAB_NOT_ALLOWED);
        assertThat(UiActionValidator.toMaps(List.of())).isNull();
    }

    @Test
    void viewerToolsExistOnlyWhenTheTurnComesFromThePlanner() {
        List<AssistantTool> beans = List.of(new ViewerActionTools(mock(LoadPlanQueryService.class)), new LinkTool());
        AssistantToolRegistry registry = new AssistantToolRegistry(beans, mock(AssistantToolCallRepository.class),
                Clock.systemUTC());

        assertThat(names(registry, ToolScope.of(UserRole.DISPATCHER))).doesNotContainAnyElementsOf(VIEWER_TOOLS);
        assertThat(names(registry, ToolScope.of(UserRole.DISPATCHER).withViewer())).containsAll(VIEWER_TOOLS);
        assertThat(names(registry, ToolScope.of(UserRole.MANAGER).withViewer())).containsAll(VIEWER_TOOLS);
        assertThat(names(registry, ToolScope.of(UserRole.DRIVER).withViewer())).doesNotContainAnyElementsOf(VIEWER_TOOLS);
        assertThat(names(registry, ToolScope.ofGuest().withViewer())).doesNotContainAnyElementsOf(VIEWER_TOOLS);
        registry.toolsFor(ToolScope.of(UserRole.DISPATCHER).withViewer(), new ToolCallLog()).forEach(tool ->
                assertThat(tool.getToolDefinition().inputSchema()).as(tool.getToolDefinition().name())
                        .doesNotContain("ToolContext").doesNotContain("\"context\""));
    }

    private static List<String> names(AssistantToolRegistry registry, ToolScope scope) {
        return registry.toolsFor(scope, new ToolCallLog()).stream().map(tool -> tool.getToolDefinition().name())
                .toList();
    }

    private static UiAction action(String type, String packageCode, String preset, Integer step, String mode,
                                   String tab) {
        return new UiAction(type, packageCode, preset, step, mode, tab);
    }

    /** {@code count} kiện mã {@code prefix + i}, bước xếp i. */
    static LoadPlanDetailResponse plan(Long id, String prefix, int count) {
        List<PlacementDetailResponse> placements = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            placements.add(PlacementDetailResponse.builder().id((long) i).packageCode(prefix + i).stepSequence(i)
                    .build());
        }
        return LoadPlanDetailResponse.builder().id(id).placements(placements).unplaced(List.of()).build();
    }
}
