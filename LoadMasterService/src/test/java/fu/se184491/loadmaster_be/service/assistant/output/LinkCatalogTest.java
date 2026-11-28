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

    /** Ba vai trò nền tảng chỉ mở màn của nền tảng theo {@code nav-items.ts} của web, không màn vận hành nào. */
    @Test
    void platformRolesOnlyGetTheirPlatformScreens() {
        assertThat(openFor(UserRole.SYSTEM_ADMIN)).containsExactlyInAnyOrder(LinkTarget.PRICING, LinkTarget.PROFILE,
                LinkTarget.COMPANIES, LinkTarget.GUEST_CONTACTS, LinkTarget.TRIAL_REQUESTS);
        assertThat(openFor(UserRole.SYSTEM_MANAGER)).containsExactlyInAnyOrder(LinkTarget.PRICING, LinkTarget.PROFILE,
                LinkTarget.COMPANIES, LinkTarget.TRIAL_REQUESTS);
        assertThat(openFor(UserRole.SYSTEM_SUPPORTER)).containsExactlyInAnyOrder(LinkTarget.PRICING,
                LinkTarget.PROFILE, LinkTarget.SUPPORT_DESK, LinkTarget.GUEST_CONTACTS);
        assertThat(link(LinkTarget.COMPANIES, null, ToolScope.of(UserRole.SYSTEM_ADMIN)))
                .contains(new AssistantLink("Công ty", "/nen-tang/cong-ty"));
        assertThat(LinkCatalog.resolve(LinkTarget.SUPPORT_DESK, null, ToolScope.of(UserRole.SYSTEM_SUPPORTER), "en"))
                .contains(new AssistantLink("Support", "/ho-tro"));
    }

    /** Màn nền tảng không mở cho vai trò của công ty; Đội xe chỉ cho quản lý công ty và điều phối viên. */
    @Test
    void companyRolesDoNotGetPlatformScreensAndFleetIsForTripViewers() {
        for (UserRole role : List.of(UserRole.ADMIN, UserRole.MANAGER, UserRole.DISPATCHER, UserRole.DRIVER,
                UserRole.WAREHOUSE_WORKER)) {
            assertThat(openFor(role)).as(role.name()).doesNotContain(LinkTarget.COMPANIES, LinkTarget.SUPPORT_DESK,
                    LinkTarget.GUEST_CONTACTS, LinkTarget.TRIAL_REQUESTS);
        }
        assertThat(link(LinkTarget.FLEET, null, ToolScope.of(UserRole.DISPATCHER))).map(AssistantLink::to)
                .contains("/doi-xe");
        assertThat(link(LinkTarget.FLEET, null, ToolScope.of(UserRole.MANAGER))).isPresent();
        assertThat(link(LinkTarget.FLEET, null, ToolScope.of(UserRole.WAREHOUSE_WORKER))).isEmpty();
        assertThat(link(LinkTarget.MONITORING, null, ToolScope.of(UserRole.DRIVER))).isEmpty();
    }

    private static List<LinkTarget> openFor(UserRole role) {
        return Arrays.stream(LinkTarget.values())
                .filter(target -> link(target, "TRIP-2026-0914", ToolScope.of(role)).isPresent()).toList();
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

    /** {@code /phieu-luong} và {@code /tra-cuu-kien} của web theo {@code payroll.viewOwn}, {@code packages.lookup}. */
    @Test
    void payslipAndPackageLookupAreForDispatchersAndWarehouseWorkers() {
        for (UserRole role : List.of(UserRole.DISPATCHER, UserRole.WAREHOUSE_WORKER)) {
            assertThat(link(LinkTarget.PAYSLIP, null, ToolScope.of(role))).as(role.name())
                    .contains(new AssistantLink("Phiếu lương", "/phieu-luong"));
            assertThat(link(LinkTarget.PACKAGE_LOOKUP, null, ToolScope.of(role))).as(role.name())
                    .map(AssistantLink::to).contains("/tra-cuu-kien");
        }
        for (UserRole role : List.of(UserRole.DRIVER, UserRole.MANAGER, UserRole.ADMIN)) {
            assertThat(link(LinkTarget.PAYSLIP, null, ToolScope.of(role))).as(role.name()).isEmpty();
            assertThat(link(LinkTarget.PACKAGE_LOOKUP, null, ToolScope.of(role))).as(role.name()).isEmpty();
        }
    }

    /** Tài xế xin nhận chuyến ở app di động; web chỉ có màn duyệt phiếu ({@code /phieu-xin-chuyen}, {@code trips.view}). */
    @Test
    void tripClaimsScreenIsForDispatchersAndManagersOnly() {
        assertThat(link(LinkTarget.TRIP_CLAIMS, null, ToolScope.of(UserRole.DISPATCHER))).map(AssistantLink::to)
                .contains("/phieu-xin-chuyen");
        assertThat(link(LinkTarget.TRIP_CLAIMS, null, ToolScope.of(UserRole.MANAGER))).isPresent();
        assertThat(link(LinkTarget.TRIP_CLAIMS, null, ToolScope.of(UserRole.DRIVER))).isEmpty();
        assertThat(link(LinkTarget.TRIP_CLAIMS, null, ToolScope.of(UserRole.WAREHOUSE_WORKER))).isEmpty();
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
