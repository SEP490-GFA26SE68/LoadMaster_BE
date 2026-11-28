package fu.se184491.loadmaster_be.service.assistant.output;

import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import fu.se184491.loadmaster_be.service.assistant.model.UiAction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Whitelist lệnh cho khung 3D (đặc tả mục 8). Trợ lý không bấm gì: nó trả lệnh, lớp này giữ lại lệnh đúng loại, đúng
 * tham số và đúng phương án đang xem, dựng lại lệnh sạch (chỉ trường của loại đó); FE kiểm lần nữa. Không có lệnh dời,
 * xoay, ghim hay duyệt. Lệnh sai bị bỏ im lặng; tối đa {@value #MAX_ACTIONS} lệnh mỗi lượt. Hàm thuần.
 */
public final class UiActionValidator {

    public static final int MAX_ACTIONS = 5;

    public static final String SELECT_PACKAGE = "selectPackage";
    public static final String SET_CAMERA_PRESET = "setCameraPreset";
    public static final String SET_STEP = "setStep";
    public static final String SET_COLOR_MODE = "setColorMode";
    public static final String OPEN_TAB = "openTab";

    /** Góc nhìn của Planner ({@code CAMERA_PRESETS} của FE). */
    public static final Set<String> PRESETS = Set.of("truoc", "cua-sau", "ben-hong", "tren", "goc-cheo", "gam-xe");
    /** Chế độ màu ({@code COLOR_MODES} của FE). */
    public static final Set<String> MODES = Set.of("diem-giao", "kien-goc", "khoi-luong");
    /** Tab của hộp thông tin ({@code LeftTab} của FE). */
    public static final Set<String> TABS = Set.of("unplaced", "pinned", "placed");

    /** Lý do một lệnh bị bỏ (công cụ báo lại cho mô hình; không bao giờ tới người dùng). */
    public static final String NO_VIEWER = "NO_VIEWER";
    public static final String UNKNOWN_ACTION = "UNKNOWN_ACTION";
    public static final String PACKAGE_NOT_IN_PLAN = "PACKAGE_NOT_IN_PLAN";
    public static final String PRESET_NOT_ALLOWED = "PRESET_NOT_ALLOWED";
    public static final String STEP_OUT_OF_RANGE = "STEP_OUT_OF_RANGE";
    public static final String MODE_NOT_ALLOWED = "MODE_NOT_ALLOWED";
    public static final String TAB_NOT_ALLOWED = "TAB_NOT_ALLOWED";

    private UiActionValidator() {
    }

    /**
     * Phương án đang xem, đủ để kiểm lệnh: mã các kiện đã xếp (khoá viết hoa → mã gốc) và số bước xếp. Bước hợp lệ là
     * 0…{@code totalSteps} (0 là trước kiện đầu tiên), như thanh bước của Planner.
     */
    public record ViewerPlan(Long loadPlanId, Map<String, String> placedCodes, int totalSteps) {

        public ViewerPlan {
            placedCodes = placedCodes == null ? Map.of() : Map.copyOf(placedCodes);
        }

        public static ViewerPlan of(LoadPlanDetailResponse plan) {
            List<PlacementDetailResponse> placements = plan.getPlacements() == null ? List.of() : plan.getPlacements();
            Map<String, String> codes = new LinkedHashMap<>();
            for (PlacementDetailResponse placement : placements) {
                for (String code : new String[]{placement.getPackageCode(), placement.getTrackingBarcode()}) {
                    if (code != null && !code.isBlank()) {
                        codes.putIfAbsent(key(code), Objects.requireNonNullElse(placement.getPackageCode(), code));
                    }
                }
            }
            int steps = placements.stream().map(PlacementDetailResponse::getStepSequence).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(placements.size());
            return new ViewerPlan(plan.getId(), codes, steps);
        }

        /** Mã gốc của kiện đã xếp khớp {@code asked} (không phân biệt hoa thường), hoặc trống. */
        public Optional<String> placedCode(String asked) {
            return asked == null ? Optional.empty() : Optional.ofNullable(placedCodes.get(key(asked)));
        }

        private static String key(String code) {
            return code.trim().toUpperCase(Locale.ROOT);
        }
    }

    /** Kết quả kiểm một lệnh: {@code action} là lệnh sạch khi hợp lệ, không thì {@code reason}. */
    public record Check(UiAction action, String reason) {

        public boolean ok() {
            return action != null;
        }

        static Check pass(UiAction action) {
            return new Check(action, null);
        }

        static Check fail(String reason) {
            return new Check(null, reason);
        }
    }

    public static Check check(UiAction action, ViewerPlan plan) {
        if (action == null || action.type() == null) {
            return Check.fail(UNKNOWN_ACTION);
        }
        if (plan == null) {
            return Check.fail(NO_VIEWER);
        }
        return switch (action.type()) {
            case SELECT_PACKAGE -> plan.placedCode(action.packageCode())
                    .map(code -> Check.pass(new UiAction(SELECT_PACKAGE, code, null, null, null, null)))
                    .orElseGet(() -> Check.fail(PACKAGE_NOT_IN_PLAN));
            case SET_CAMERA_PRESET -> slug(action.preset(), PRESETS)
                    .map(preset -> Check.pass(new UiAction(SET_CAMERA_PRESET, null, preset, null, null, null)))
                    .orElseGet(() -> Check.fail(PRESET_NOT_ALLOWED));
            case SET_STEP -> action.step() != null && action.step() >= 0 && action.step() <= plan.totalSteps()
                    ? Check.pass(new UiAction(SET_STEP, null, null, action.step(), null, null))
                    : Check.fail(STEP_OUT_OF_RANGE);
            case SET_COLOR_MODE -> slug(action.mode(), MODES)
                    .map(mode -> Check.pass(new UiAction(SET_COLOR_MODE, null, null, null, mode, null)))
                    .orElseGet(() -> Check.fail(MODE_NOT_ALLOWED));
            case OPEN_TAB -> slug(action.tab(), TABS)
                    .map(tab -> Check.pass(new UiAction(OPEN_TAB, null, null, null, null, tab)))
                    .orElseGet(() -> Check.fail(TAB_NOT_ALLOWED));
            default -> Check.fail(UNKNOWN_ACTION);
        };
    }

    /** Lệnh giữ lại theo thứ tự, bỏ lệnh sai và lệnh trùng, tối đa {@value #MAX_ACTIONS}. Không có phương án thì rỗng. */
    public static List<UiAction> filter(List<UiAction> actions, ViewerPlan plan) {
        if (actions == null || actions.isEmpty() || plan == null) {
            return List.of();
        }
        Set<UiAction> kept = new LinkedHashSet<>();
        for (UiAction action : actions) {
            Check check = check(action, plan);
            if (check.ok()) {
                kept.add(check.action());
                if (kept.size() >= MAX_ACTIONS) {
                    break;
                }
            }
        }
        return List.copyOf(kept);
    }

    /** Dạng lưu ở cột {@code ui_actions}: chỉ các trường có giá trị. */
    public static Map<String, Object> toMap(UiAction action) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", action.type());
        putIfPresent(out, "packageCode", action.packageCode());
        putIfPresent(out, "preset", action.preset());
        putIfPresent(out, "step", action.step());
        putIfPresent(out, "mode", action.mode());
        putIfPresent(out, "tab", action.tab());
        return out;
    }

    /** Danh sách để lưu; rỗng thì {@code null} như các tin trước khi có lệnh. */
    public static List<Map<String, Object>> toMaps(List<UiAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>(actions.size());
        actions.forEach(action -> out.add(toMap(action)));
        return out;
    }

    private static Optional<String> slug(String value, Set<String> allowed) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? Optional.of(normalized) : Optional.empty();
    }

    private static void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }
}
