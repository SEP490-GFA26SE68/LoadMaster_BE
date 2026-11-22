package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.account.UserProfileResponse;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionCurrentResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.account.UserService;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyAccountToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final UserService users = mock(UserService.class);
    private final SubscriptionService subscriptions = mock(SubscriptionService.class);
    private final MyAccountTool tool = new MyAccountTool(users, subscriptions);

    @BeforeEach
    void signIn() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("kc-8").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private void profile(UserRole role, Long companyId, Boolean expired) {
        when(users.getUserProfile(any())).thenReturn(UserProfileResponse.builder()
                .id(8L).fullName("Trần Minh Khoa").userRoleType(role).companyId(companyId)
                .companyName(companyId == null ? null : "Vận tải Long Bình").subscriptionExpired(expired)
                .email("khoa@longbinh.vn").phoneNumber("0900000000").build());
    }

    private JsonNode call() {
        return JSON.readTree(ToolResults.json(tool.getMyAccount()));
    }

    @Test
    void driverGetsTheProfileOnlyAndTheSubscriptionServiceIsNotCalled() {
        profile(UserRole.DRIVER, 1L, false);

        String json = ToolResults.json(tool.getMyAccount());

        assertThat(json).doesNotContain("creditsBalance").doesNotContain("subscription\"")
                .doesNotContain("khoa@longbinh.vn").doesNotContain("0900000000");
        JsonNode out = JSON.readTree(json);
        assertThat(out.get("fullName").asString()).isEqualTo("Trần Minh Khoa");
        assertThat(out.get("role").asString()).isEqualTo("DRIVER");
        assertThat(out.get("companyName").asString()).isEqualTo("Vận tải Long Bình");
        assertThat(out.get("subscriptionLocked").asBoolean()).isFalse();
        verify(subscriptions, never()).getCurrentSubscription(anyLong());
    }

    @Test
    void dispatcherAlsoGetsPlanExpiryAndBalance() {
        profile(UserRole.DISPATCHER, 1L, false);
        when(subscriptions.getCurrentSubscription(1L)).thenReturn(SubscriptionCurrentResponse.builder()
                .planName("Gói Chuyên Nghiệp (Pro)").tier(SubscriptionTier.PRO).status(SubscriptionStatus.ACTIVE)
                .expiresAt(LocalDateTime.of(2026, 12, 20, 9, 0)).autoRenew(true)
                .creditsBalance(8412).creditsMonthlyGrant(10000).subscriptionId(5L).planId(2L).build());

        JsonNode subscription = call().get("subscription");

        assertThat(subscription.get("planName").asString()).isEqualTo("Gói Chuyên Nghiệp (Pro)");
        assertThat(subscription.get("creditsBalance").asInt()).isEqualTo(8412);
        assertThat(subscription.get("expiresAt").asString()).isEqualTo("2026-12-20T09:00:00");
        assertThat(subscription.get("unlimitedCredits").asBoolean()).isFalse();
        assertThat(subscription.has("subscriptionId")).isFalse();
        assertThat(subscription.has("planId")).isFalse();
    }

    @Test
    void ultimateHasNoBalanceButUnlimitedCredits() {
        profile(UserRole.ADMIN, 1L, false);
        when(subscriptions.getCurrentSubscription(1L)).thenReturn(SubscriptionCurrentResponse.builder()
                .planName("Gói Toàn Diện (Ultimate AI)").tier(SubscriptionTier.ULTIMATE)
                .status(SubscriptionStatus.ACTIVE).creditsBalance(0).creditsMonthlyGrant(null).build());

        JsonNode subscription = call().get("subscription");

        assertThat(subscription.has("creditsBalance")).isFalse();
        assertThat(subscription.get("unlimitedCredits").asBoolean()).isTrue();
    }

    @Test
    void companyWithoutValidPlanIsReportedLocked() {
        profile(UserRole.MANAGER, 1L, true);
        when(subscriptions.getCurrentSubscription(1L))
                .thenReturn(SubscriptionCurrentResponse.builder().creditsBalance(0).build());

        JsonNode out = call();

        assertThat(out.get("subscriptionLocked").asBoolean()).isTrue();
        assertThat(out.get("subscription").has("planName")).isFalse();
    }

    @Test
    void platformAccountHasNoCompanyFields() {
        profile(UserRole.SYSTEM_SUPPORTER, null, false);

        JsonNode out = call();

        assertThat(out.has("companyName")).isFalse();
        assertThat(out.has("subscriptionLocked")).isFalse();
        assertThat(out.has("subscription")).isFalse();
        verify(subscriptions, never()).getCurrentSubscription(anyLong());
    }

    @Test
    void withoutASignedInUserTheToolRefuses() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(tool::getMyAccount).isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    }
}
