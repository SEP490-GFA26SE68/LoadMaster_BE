package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.support.ContactRequestType;
import fu.se184491.loadmaster_be.constant.support.ContactStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.response.support.ContactOfGuestResponse;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.support.ContactOfGuestService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Liên hệ của khách qua hàm của màn: không email, số điện thoại, mã số thuế; lời nhắn cắt bớt; giữ thứ tự Mới trước. */
class GuestContactsToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ContactOfGuestService service = mock(ContactOfGuestService.class);
    private final GuestContactsTool tool = new GuestContactsTool(service);

    @Test
    void onlyTheTwoRolesThatHandleContactsGetIt() {
        assertThat(tool.roles()).containsExactlyInAnyOrder(UserRole.SYSTEM_ADMIN, UserRole.SYSTEM_SUPPORTER);
    }

    @Test
    void contactsCarryNoEmailPhoneOrTaxCodeAndLongMessagesAreClipped() {
        ContactOfGuestResponse contact = ContactOfGuestResponse.builder().id(12L).fullName("Nguyễn Văn An")
                .email("an@khach.vn").phoneNumber("0901234567").companyName("Vận tải An Phát").companyCode("AP")
                .taxCode("0312345678").interestedPlanId(2L).interestedPlanName("Pro").assignedToId(8L)
                .assignedToName("Trần Hỗ Trợ").message("x".repeat(400)).note("Ghi chú nội bộ")
                .requestType(ContactRequestType.PRICING).status(ContactStatus.NEW)
                .createdAt(Instant.parse("2026-11-24T01:00:00Z")).build();
        when(service.getContactsPaged(any(), any(), any(), any())).thenReturn(PageResponse.<ContactOfGuestResponse>builder()
                .data(List.of(contact)).totalElements(41).build());

        String json = ToolResults.json(tool.listGuestContacts(null, null));
        JsonNode out = JSON.readTree(json);

        assertThat(out.get("total").asInt()).isEqualTo(41);
        JsonNode row = out.get("rows").get(0);
        assertThat(row.get("fullName").asString()).isEqualTo("Nguyễn Văn An");
        assertThat(row.get("message").asString()).hasSize(GuestContactsTool.MAX_MESSAGE + 1).endsWith("…");
        assertThat(json).doesNotContain("an@khach.vn").doesNotContain("0901234567").doesNotContain("0312345678")
                .doesNotContain("Ghi chú nội bộ").doesNotContain("\"id\"").doesNotContain("assignedToId");
    }

    @Test
    void noStatusFilterKeepsTheScreensNewFirstOrder() {
        when(service.getContactsPaged(any(), any(), any(), any())).thenReturn(PageResponse.<ContactOfGuestResponse>builder()
                .data(List.of()).build());

        tool.listGuestContacts(null, "demo_request");
        tool.listGuestContacts("in_progress", null);

        ArgumentCaptor<Pageable> windows = ArgumentCaptor.forClass(Pageable.class);
        verify(service).getContactsPaged(isNull(), eq(ContactRequestType.DEMO_REQUEST), isNull(), windows.capture());
        verify(service).getContactsPaged(eq(ContactStatus.IN_PROGRESS), isNull(), isNull(), windows.capture());
        assertThat(windows.getAllValues().get(0).getSort().isUnsorted()).isTrue();
        assertThat(windows.getAllValues().get(1).getSort().isSorted()).isTrue();
        assertThat(windows.getAllValues()).allSatisfy(w -> assertThat(w.getPageSize()).isEqualTo(ToolResults.LIST_ROWS));
    }

    @Test
    void unknownFiltersAreRejected() {
        assertThat(tool.listGuestContacts("DONE", null)).isEqualTo(ToolResults.error(ToolResults.INVALID_FILTER));
        assertThat(tool.listGuestContacts(null, "SPAM")).isEqualTo(ToolResults.error(ToolResults.INVALID_FILTER));
        verifyNoInteractions(service);
    }
}
