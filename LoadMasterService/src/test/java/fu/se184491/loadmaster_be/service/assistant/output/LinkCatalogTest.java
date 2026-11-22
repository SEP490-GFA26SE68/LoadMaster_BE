package fu.se184491.loadmaster_be.service.assistant.output;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantLink;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolScope;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LinkCatalogTest {

    private static Optional<AssistantLink> link(LinkTarget target, String tripCode, ToolScope scope) {
        return LinkCatalog.resolve(target, tripCode, scope, "vi");
    }

    @Test
    void driverCannotOpenOptimizationSetup() {
        assertThat(link(LinkTarget.OPTIMIZATION_SETUP, "TRIP-2026-0914", ToolScope.of(UserRole.DRIVER))).isEmpty();
        assertThat(link(LinkTarget.OPTIMIZATION_SETUP, "TRIP-2026-0914", ToolScope.of(UserRole.MANAGER))).isEmpty();
    }

    @Test
    void dispatcherWithTripCodeGetsTheSetupScreenOfThatTrip() {
        assertThat(link(LinkTarget.OPTIMIZATION_SETUP, "TRIP-2026-0914", ToolScope.of(UserRole.DISPATCHER)))
                .contains(new AssistantLink("Thiết lập tối ưu chuyến TRIP-2026-0914", "/chuyen/TRIP-2026-0914/toi-uu"));
        assertThat(LinkCatalog.resolve(LinkTarget.COMPARE, " trip-2026-0914 ", ToolScope.of(UserRole.MANAGER), "en"))
                .contains(new AssistantLink("Compare plans for TRIP-2026-0914", "/chuyen/TRIP-2026-0914/so-sanh"));
    }

    @Test
    void malformedOrMissingTripCodeGivesNoLink() {
        ToolScope dispatcher = ToolScope.of(UserRole.DISPATCHER);
        for (String code : Arrays.asList(null, "", "2026-0914", "TRIP-", "TRIP-1/../../admin", "TRIP-1?x=1",
                "https://evil.example/TRIP-1", "TRIP-" + "A".repeat(41))) {
            assertThat(link(LinkTarget.TRIP_DETAIL, code, dispatcher)).as(String.valueOf(code)).isEmpty();
        }
    }

    @Test
    void guestsOnlyGetPublicPages() {
        ToolScope guest = ToolScope.ofGuest();
        assertThat(link(LinkTarget.PRICING, null, guest)).contains(new AssistantLink("Bảng giá", "/bang-gia"));
        assertThat(link(LinkTarget.TRIAL_SIGNUP, null, guest)).map(AssistantLink::to).contains("/dang-ky-trai-nghiem");
        for (LinkTarget target : LinkTarget.values()) {
            if (target != LinkTarget.PRICING && target != LinkTarget.TRIAL_SIGNUP) {
                assertThat(link(target, "TRIP-2026-0914", guest)).as(target.name()).isEmpty();
            }
        }
        assertThat(link(LinkTarget.TRIAL_SIGNUP, null, ToolScope.of(UserRole.ADMIN))).isEmpty();
    }

    @Test
    void platformRolesOnlyGetPricingAndProfile() {
        for (UserRole role : List.of(UserRole.SYSTEM_ADMIN, UserRole.SYSTEM_MANAGER, UserRole.SYSTEM_SUPPORTER)) {
            List<LinkTarget> open = Arrays.stream(LinkTarget.values())
                    .filter(target -> link(target, "TRIP-2026-0914", ToolScope.of(role)).isPresent()).toList();
            assertThat(open).as(role.name()).containsExactlyInAnyOrder(LinkTarget.PRICING, LinkTarget.PROFILE);
        }
    }

    @Test
    void screensFollowTheWebPermissions() {
        assertThat(link(LinkTarget.PAYROLL, null, ToolScope.of(UserRole.DRIVER))).isEmpty();
        assertThat(link(LinkTarget.PAYROLL, null, ToolScope.of(UserRole.MANAGER))).map(AssistantLink::to)
                .contains("/bang-luong");
        assertThat(link(LinkTarget.DRIVER_TRIPS, null, ToolScope.of(UserRole.DRIVER))).map(AssistantLink::to)
                .contains("/tai-xe");
        assertThat(link(LinkTarget.TRIPS, null, ToolScope.of(UserRole.WAREHOUSE_WORKER))).isEmpty();
        assertThat(link(LinkTarget.PACKAGES, null, ToolScope.of(UserRole.WAREHOUSE_WORKER))).map(AssistantLink::to)
                .contains("/kien-hang");
        assertThat(link(LinkTarget.BILLING, null, ToolScope.of(UserRole.DISPATCHER))).isEmpty();
        assertThat(link(LinkTarget.BILLING, null, ToolScope.of(UserRole.ADMIN))).map(AssistantLink::to)
                .contains("/goi-cuoc");
    }

    @Test
    void everyTargetOpensForSomeone() {
        for (LinkTarget target : LinkTarget.values()) {
            boolean someone = link(target, "TRIP-2026-0914", ToolScope.ofGuest()).isPresent()
                    || Arrays.stream(UserRole.values())
                    .anyMatch(role -> link(target, "TRIP-2026-0914", ToolScope.of(role)).isPresent());
            assertThat(someone).as(target.name()).isTrue();
        }
    }
}
