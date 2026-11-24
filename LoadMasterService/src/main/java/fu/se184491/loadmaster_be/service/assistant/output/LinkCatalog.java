package fu.se184491.loadmaster_be.service.assistant.output;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantLink;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolScope;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static fu.se184491.loadmaster_be.constant.account.UserRole.ADMIN;
import static fu.se184491.loadmaster_be.constant.account.UserRole.DISPATCHER;
import static fu.se184491.loadmaster_be.constant.account.UserRole.DRIVER;
import static fu.se184491.loadmaster_be.constant.account.UserRole.MANAGER;
import static fu.se184491.loadmaster_be.constant.account.UserRole.WAREHOUSE_WORKER;

/**
 * Bảng đích liên kết của trợ lý: đường dẫn (route của web), nhãn vi / en và vai trò mở được, theo quyền của màn ở web
 * ({@code App.tsx}, {@code permissions.ts}). Mô hình chỉ chọn đích; đường dẫn và nhãn do đây dựng, đích ngoài quyền của
 * người hỏi thì không có liên kết.
 */
public final class LinkCatalog {

    /** Mã chuyến trên URL của web; cùng dạng với {@code AssistantPromptBuilder}. */
    private static final Pattern TRIP_CODE = Pattern.compile("^TRIP-[A-Z0-9-]{1,40}$");

    private static final Set<UserRole> EVERYONE = EnumSet.allOf(UserRole.class);
    private static final Set<UserRole> TRIP_VIEWERS = EnumSet.of(MANAGER, DISPATCHER);

    /** {@code path} có {@code %s} là chỗ của mã chuyến; nhãn cũng vậy. */
    private record Entry(String path, Set<UserRole> roles, boolean guest, String vi, String en) {
        boolean needsTrip() {
            return path.contains("%s");
        }
    }

    private static final Map<LinkTarget, Entry> ENTRIES = new EnumMap<>(LinkTarget.class);

    static {
        put(LinkTarget.PRICING, "/bang-gia", EVERYONE, true, "Bảng giá", "Pricing");
        put(LinkTarget.TRIAL_SIGNUP, "/dang-ky-trai-nghiem", Set.of(), true, "Đăng ký trải nghiệm",
                "Sign up for a trial");
        put(LinkTarget.TRIPS, "/chuyen", TRIP_VIEWERS, false, "Chuyến hàng", "Trips");
        put(LinkTarget.TRIP_DETAIL, "/chuyen/%s", TRIP_VIEWERS, false, "Chuyến %s", "Trip %s");
        put(LinkTarget.OPTIMIZATION_SETUP, "/chuyen/%s/toi-uu", EnumSet.of(DISPATCHER), false,
                "Thiết lập tối ưu chuyến %s", "Optimization setup for %s");
        put(LinkTarget.PLANNER, "/chuyen/%s/phuong-an", TRIP_VIEWERS, false, "Phương án xếp hàng chuyến %s",
                "Load plan for %s");
        put(LinkTarget.COMPARE, "/chuyen/%s/so-sanh", TRIP_VIEWERS, false, "So sánh phương án chuyến %s",
                "Compare plans for %s");
        put(LinkTarget.MONITORING, "/giam-sat", TRIP_VIEWERS, false, "Giám sát", "Monitoring");
        put(LinkTarget.REQUIREMENTS, "/yeu-cau-giao", TRIP_VIEWERS, false, "Yêu cầu giao", "Requirements");
        put(LinkTarget.PACKAGES, "/kien-hang", EnumSet.of(MANAGER, DISPATCHER, WAREHOUSE_WORKER), false, "Kho kiện",
                "Package pool");
        put(LinkTarget.BILLING, "/goi-cuoc", EnumSet.of(ADMIN), false, "Gói và credit", "Plan and credits");
        // Tài xế xem phiếu lương ở app di động, không có màn bảng lương trên web
        put(LinkTarget.PAYROLL, "/bang-luong", EnumSet.of(MANAGER), false, "Bảng lương", "Payroll");
        put(LinkTarget.PROFILE, "/ho-so", EVERYONE, false, "Hồ sơ cá nhân", "My profile");
        put(LinkTarget.DRIVER_TRIPS, "/tai-xe", EnumSet.of(DRIVER), false, "Chuyến của tôi", "My trips");
        put(LinkTarget.WAREHOUSE, "/kho", EnumSet.of(WAREHOUSE_WORKER), false, "Kho", "Warehouse");
        // Phiếu lương của chính mình (payroll.viewOwn): điều phối viên và nhân viên kho; tài xế xem ở app di động
        put(LinkTarget.PAYSLIP, "/phieu-luong", EnumSet.of(DISPATCHER, WAREHOUSE_WORKER), false, "Phiếu lương",
                "My payslip");
        put(LinkTarget.PACKAGE_LOOKUP, "/tra-cuu-kien", EnumSet.of(DISPATCHER, WAREHOUSE_WORKER), false,
                "Tra cứu kiện", "Package lookup");
        // Phiếu xin nhận chuyến của tài xế; tài xế xin nhận ở tab "Chuyến đang mở" của app di động
        put(LinkTarget.TRIP_CLAIMS, "/phieu-xin-chuyen", TRIP_VIEWERS, false, "Phiếu xin chuyến", "Trip claims");
        if (ENTRIES.size() != LinkTarget.values().length) {
            throw new IllegalStateException("Thiếu đích liên kết trong LinkCatalog");
        }
    }

    private LinkCatalog() {
    }

    private static void put(LinkTarget target, String path, Set<UserRole> roles, boolean guest, String vi, String en) {
        ENTRIES.put(target, new Entry(path, Set.copyOf(roles), guest, vi, en));
    }

    /**
     * Liên kết tới {@code target} cho người hỏi, hoặc rỗng khi họ không mở được màn đó, đích cần mã chuyến mà mã thiếu
     * hay sai dạng. {@code locale} là {@code vi} hoặc {@code en} (khác thì theo {@code vi}).
     */
    public static Optional<AssistantLink> resolve(LinkTarget target, String tripCode, ToolScope scope, String locale) {
        Entry entry = target == null || scope == null ? null : ENTRIES.get(target);
        if (entry == null || !allowed(entry, scope)) {
            return Optional.empty();
        }
        String label = "en".equals(locale) ? entry.en() : entry.vi();
        if (!entry.needsTrip()) {
            return Optional.of(new AssistantLink(label, entry.path()));
        }
        String code = tripCode == null ? "" : tripCode.strip().toUpperCase(Locale.ROOT);
        if (!TRIP_CODE.matcher(code).matches()) {
            return Optional.empty();
        }
        return Optional.of(new AssistantLink(label.formatted(code), entry.path().formatted(code)));
    }

    private static boolean allowed(Entry entry, ToolScope scope) {
        return scope.guest() ? entry.guest() : !Collections.disjoint(entry.roles(), scope.roles());
    }
}
