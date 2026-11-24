package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.support.AiPrecheckStatus;
import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.response.company.CompanyResponse;
import fu.se184491.loadmaster_be.dto.response.support.TrialAccountRequestResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.company.CompanyService;
import fu.se184491.loadmaster_be.service.support.TrialAccountRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Công ty và đơn dùng thử cho hai vai trò nền tảng: trạng thái gói tính tới lúc này, không lộ liên hệ của khách. */
class PlatformCompaniesToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 9, 0);

    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final CompanyService companies = mock(CompanyService.class);
    private final TrialAccountRequestService trials = mock(TrialAccountRequestService.class);
    private final PlatformCompaniesTool tool = new PlatformCompaniesTool(currentUser, companies, trials,
            Clock.fixed(NOW.atZone(VN).toInstant(), VN));

    @Test
    void onlyThePlatformAdminAndManagerGetIt() {
        assertThat(tool.roles()).containsExactlyInAnyOrder(UserRole.SYSTEM_ADMIN, UserRole.SYSTEM_MANAGER);
    }

    @Test
    void planStatusIsNoneWithoutAPlanAndExpiredOncePastItsEnd() {
        assertThat(PlatformCompaniesTool.planStatus(CompanyResponse.builder().build(), NOW)).isEqualTo("NONE");
        assertThat(PlatformCompaniesTool.planStatus(CompanyResponse.builder().subscriptionStatus("ACTIVE")
                .subscriptionExpiresAt(NOW.minusMinutes(1)).build(), NOW)).isEqualTo("EXPIRED");
        assertThat(PlatformCompaniesTool.planStatus(CompanyResponse.builder().subscriptionStatus("TRIAL")
                .subscriptionExpiresAt(NOW.plusDays(3)).build(), NOW)).isEqualTo("TRIAL");
        assertThat(PlatformCompaniesTool.planStatus(CompanyResponse.builder().subscriptionStatus("CANCELLED")
                .build(), NOW)).isEqualTo("CANCELLED");
    }

    @Test
    void companiesCarryNoContactDetailsOrIds() {
        CompanyResponse company = CompanyResponse.builder().id(4L).companyCode("LOG-002").companyName("Vận tải Phương Nam")
                .taxCode("0319999999").billingEmail("ketoan@phuongnam.vn").status(CompanyStatus.ACTIVE)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z")).planName("Basic").planTier("BASIC")
                .subscriptionStatus("ACTIVE").subscriptionExpiresAt(NOW.minusDays(1)).userCount(5)
                .adminName("Lâm Viết").adminEmail("viet@phuongnam.vn").adminPhone("0988000111").adminId(31L)
                .creditBalance(2).build();
        when(companies.getAllCompanies(any(), any())).thenReturn(new PageImpl<>(List.of(company), PageRequest.of(0, 30), 1));

        String json = ToolResults.json(tool.listCompanies("  phương  "));
        JsonNode row = JSON.readTree(json).get("rows").get(0);

        verify(companies).getAllCompanies(eq("phương"), any());
        assertThat(row.get("planStatus").asString()).isEqualTo("EXPIRED");
        assertThat(row.get("creditBalance").asInt()).isEqualTo(2);
        assertThat(json).doesNotContain("@").doesNotContain("0988000111").doesNotContain("0319999999")
                .doesNotContain("Lâm Viết").doesNotContain("\"id\"").doesNotContain("adminId");
    }

    @Test
    void trialRequestsDefaultToWhatIsWaitingForTheCaller() {
        when(trials.getTrialRequests(any(), any(), any())).thenReturn(PageResponse.<TrialAccountRequestResponse>builder()
                .data(List.of(TrialAccountRequestResponse.builder().id(9L).fullName("Ngô Khách").email("khach@an.vn")
                        .phoneNumber("0900000000").taxCode("0311111111").companyName("Kho vận An Khang")
                        .interestedPlanName("Basic").status(TrialRequestStatus.PENDING)
                        .aiPrecheckStatus(AiPrecheckStatus.PASSED).aiPrecheckReason("Mã số thuế hợp lệ")
                        .managerReviewedByEmail("nentang@loadmaster.vn").trialDays(14).trialCredits(100).build()))
                .totalElements(1).build());

        when(currentUser.getCurrentUser()).thenReturn(User.builder().userRoleType(UserRole.SYSTEM_MANAGER).build());
        String json = ToolResults.json(tool.listTrialRequests(null));
        when(currentUser.getCurrentUser()).thenReturn(User.builder().userRoleType(UserRole.SYSTEM_ADMIN).build());
        tool.listTrialRequests(" ");

        verify(trials).getTrialRequests(isNull(), eq(TrialRequestStatus.PENDING), any());
        verify(trials).getTrialRequests(isNull(), eq(TrialRequestStatus.PENDING_ADMIN_CREATION), any());
        assertThat(JSON.readTree(json).get("rows").get(0).get("companyName").asString()).isEqualTo("Kho vận An Khang");
        assertThat(json).doesNotContain("@").doesNotContain("0900000000").doesNotContain("0311111111")
                .doesNotContain("Ngô Khách").doesNotContain("\"id\"");
        assertThat(tool.listTrialRequests("waiting")).isEqualTo(ToolResults.error(ToolResults.INVALID_FILTER));
    }
}
