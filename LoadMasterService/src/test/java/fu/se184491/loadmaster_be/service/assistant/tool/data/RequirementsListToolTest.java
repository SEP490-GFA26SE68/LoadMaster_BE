package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.dto.response.planning.DeliveryRequirementResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.planning.DeliveryRequirementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Yêu cầu giao qua đúng hàm tìm của màn, theo công ty người hỏi, hạn gần nhất trước, giao thiếu đọc là PARTIAL. */
class RequirementsListToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long COMPANY = 3L;

    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final DeliveryRequirementService service = mock(DeliveryRequirementService.class);
    private final RequirementsListTool tool = new RequirementsListTool(currentUser, service);

    @BeforeEach
    void signedIn() {
        when(currentUser.getCurrentCompanyId()).thenReturn(COMPANY);
    }

    @Test
    void onlyDispatchersAndManagersGetIt() {
        assertThat(tool.roles()).containsExactlyInAnyOrder(UserRole.DISPATCHER, UserRole.MANAGER);
    }

    @Test
    void searchesTheCallersCompanyWithThirtyRowsByDeadlineAndAWholeDayWindow() {
        when(service.search(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 30), 0));

        tool.listDeliveryRequirements(" pending ", "urgent", "2026-11-24", "2026-11-25");

        ArgumentCaptor<Pageable> window = ArgumentCaptor.forClass(Pageable.class);
        verify(service).search(eq(COMPANY), isNull(), eq("PENDING"), eq("URGENT"),
                eq(LocalDateTime.of(2026, 11, 24, 0, 0)), eq(LocalDateTime.of(2026, 11, 26, 0, 0)), eq(true),
                window.capture());
        assertThat(window.getValue().getPageSize()).isEqualTo(ToolResults.LIST_ROWS);
        assertThat(window.getValue().getSort()).isEqualTo(Sort.by("deadline"));
    }

    @Test
    void shortDeliveredIsPartialAndNoIdsOrCoordinatesLeak() {
        DeliveryRequirementResponse partial = DeliveryRequirementResponse.builder().id(41L).companyId(COMPANY)
                .customerId(9L).customerName("Điện máy Xanh").destination("Dĩ An, Bình Dương")
                .destinationLat(new BigDecimal("10.9061")).destinationLng(new BigDecimal("106.7697"))
                .deadline(LocalDateTime.of(2026, 11, 24, 17, 0)).priority(DeliveryRequirementPriority.HIGH)
                .status(DeliveryRequirementStatus.ASSIGNED).shortDelivered(true).droppedPackageCount(2)
                .receivedPackageCount(10).expectedPackageCount(12).packageIds(List.of(501L, 502L)).tripId(77L)
                .tripCode("TRIP-AST-1").createdBy(5L).build();
        DeliveryRequirementResponse pending = DeliveryRequirementResponse.builder().customerName("Thế Giới Di Động")
                .status(DeliveryRequirementStatus.PENDING).droppedPackageCount(0).build();
        when(service.search(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(partial, pending), PageRequest.of(0, 30), 47));

        String json = ToolResults.json(tool.listDeliveryRequirements(null, null, null, null));
        JsonNode out = JSON.readTree(json);

        assertThat(out.get("total").asInt()).isEqualTo(47);
        assertThat(out.get("rows").get(0).get("status").asString()).isEqualTo("PARTIAL");
        assertThat(out.get("rows").get(0).get("droppedPackages").asInt()).isEqualTo(2);
        assertThat(out.get("rows").get(1).get("status").asString()).isEqualTo("PENDING");
        assertThat(out.get("rows").get(1).has("droppedPackages")).isFalse();
        assertThat(json).doesNotContain("\"id\"").doesNotContain("10.9061").doesNotContain("106.7697")
                .doesNotContain("501").doesNotContain("companyId").doesNotContain("createdBy");
    }

    @Test
    void wrongFiltersAreRejectedWithoutReadingAnything() {
        String invalid = ToolResults.error(ToolResults.INVALID_FILTER);

        assertThat(tool.listDeliveryRequirements("DELIVERED", null, null, null)).isEqualTo(invalid);
        assertThat(tool.listDeliveryRequirements(null, "CRITICAL", null, null)).isEqualTo(invalid);
        assertThat(tool.listDeliveryRequirements(null, null, "24/11/2026", null)).isEqualTo(invalid);
        assertThat(tool.listDeliveryRequirements(null, null, "2026-11-25", "2026-11-23")).isEqualTo(invalid);
        verifyNoInteractions(service);
    }
}
