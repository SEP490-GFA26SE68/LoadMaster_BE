package fu.se184491.loadmaster_be.service.pickup;

import fu.se184491.loadmaster_be.constant.monitoring.DeadlineStatus;
import fu.se184491.loadmaster_be.service.monitoring.DeadlineStatusRule;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.AxleLoads;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.CenterOfGravity;
import fu.se184491.loadmaster_be.service.pickup.PickupRoutePath.Point;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongBiFunction;

/**
 * Mười luật nhận hàng dọc đường, tính ở máy chủ. Thuần: mọi thứ luật cần nằm trong {@link Context}; mỗi luật trả mã + tham số (mã giống
 * hệt mã của web, để web dịch như bản kho mẫu). Mọi luật luôn được đánh giá — luật trước không đạt không bỏ qua luật sau.
 *
 * <p>Luật 4–7 và 10 đọc kết quả xếp thật kiện nhận vào phần thùng đang trống ({@link FreedSpacePacker}).</p>
 */
public final class PickupRules {

    /** Luật 1: điểm nhận cách tuyến còn lại của xe tối đa chừng này, km. */
    public static final double MAX_ROUTE_DISTANCE_KM = 10;
    /** Hai toạ độ cách nhau không quá chừng này là cùng một điểm (điểm giao dùng lại điểm có sẵn), km. */
    public static final double SAME_PLACE_KM = 0.05;
    /** Trọng tâm cao quá chừng này phần chiều cao lòng thùng là lệch cao. */
    static final double COG_HEIGHT_RATIO = 0.5;
    public static final double DEFAULT_MAX_COG_OFFSET_RATIO = 0.15;
    private static final double EPSILON = 1e-6;

    private PickupRules() {
    }

    /** Một điểm của chuyến theo thứ tự tuyến. {@code onboardCount} > 0: điểm còn kiện trên xe (điểm được bảo vệ của luật 2). */
    public record RouteStop(String stopId, Point location, boolean completed, int onboardCount, boolean deliveryKind) {
    }

    public record Cargo(double lengthCm, double widthCm, double heightCm, double weightKg, String handlingClass) {
    }

    public record Request(Point pickup, Point delivery, LocalDateTime deadline, List<Cargo> packages) {
    }

    public record VehicleLimits(double maxPayloadKg, double lengthCm, double widthCm, double heightCm,
                                Double frontAxleLimitKg, Double rearAxleLimitKg, Double maxCogOffsetRatio) {
    }

    /**
     * @param onboardKg   khối lượng hàng còn trên xe lúc kiện nhận lên, kể cả kiện của yêu cầu nhận đã duyệt trước
     * @param lockedClass loại hàng đang khoá của chuyến; null khi chuyến chưa có kiện nào
     * @param duration    thời gian chạy giữa hai điểm, giây
     */
    public record Context(Request request, VehicleLimits vehicle, Point position, LocalDateTime at, List<RouteStop> stops,
                          double onboardKg, FreedSpacePacker.Result packing, String lockedClass, String overrideReason,
                          ToLongBiFunction<Point, Point> duration) {
    }

    public record RuleResult(int rule, boolean passed, String code, Map<String, Object> params) {
    }

    /**
     * Chỗ chèn điểm nhận và điểm giao: ngay sau điểm hiện tại, điểm nhận trước. {@code reusedIndex}: điểm giao có sẵn (sau điểm hiện
     * tại, không quá điểm được bảo vệ, cách điểm giao không quá {@link #SAME_PLACE_KM}) được dùng lại; -1 là thêm điểm giao mới.
     */
    public record Insertion(int currentIndex, int protectedIndex, int reusedIndex) {
    }

    public static int currentIndex(List<RouteStop> stops) {
        for (int i = 0; i < stops.size(); i++) {
            if (!stops.get(i).completed()) {
                return i;
            }
        }
        return -1;
    }

    public static int protectedIndex(List<RouteStop> stops, int current) {
        for (int i = current + 1; i < stops.size(); i++) {
            if (stops.get(i).onboardCount() > 0) {
                return i;
            }
        }
        return -1;
    }

    /** Null khi không còn điểm nào chưa hoàn tất. */
    public static Insertion insertion(List<RouteStop> stops, Point delivery) {
        int current = currentIndex(stops);
        if (current < 0) {
            return null;
        }
        int protectedIndex = protectedIndex(stops, current);
        int end = protectedIndex < 0 ? stops.size() - 1 : protectedIndex;
        for (int i = current + 1; i <= end; i++) {
            RouteStop stop = stops.get(i);
            if (stop.deliveryKind() && stop.location() != null
                    && PickupRoutePath.haversineKm(stop.location(), delivery) <= SAME_PLACE_KM) {
                return new Insertion(current, protectedIndex, i);
            }
        }
        return new Insertion(current, protectedIndex, -1);
    }

    public static List<RuleResult> evaluate(Context context) {
        return List.of(routeRule(context), deliveryRule(context), payloadRule(context), freedSpaceRule(context),
                axleRule(context), centerOfGravityRule(context), stackingRule(context), handlingClassRule(context),
                deadlineRule(context), blockingRule(context));
    }

    public static boolean allPassed(List<RuleResult> results) {
        return results.stream().allMatch(RuleResult::passed);
    }

    // --- luật 1, 2, 9: tuyến ---------------------------------------------------------------------

    private static RuleResult noStop(int rule) {
        return new RuleResult(rule, false, "PICKUP_NO_REMAINING_STOP", Map.of());
    }

    static RuleResult routeRule(Context context) {
        int current = currentIndex(context.stops());
        if (current < 0) {
            return noStop(1);
        }
        List<Point> route = new ArrayList<>();
        route.add(context.position());
        context.stops().subList(current, context.stops().size()).forEach(stop -> route.add(stop.location()));
        PickupRoutePath.Location location = PickupRoutePath.locate(route, context.request().pickup());
        Map<String, Object> params = params("distanceKm", round2(location.distanceKm()), "maxKm", MAX_ROUTE_DISTANCE_KM);
        String code = location.distanceKm() > MAX_ROUTE_DISTANCE_KM + EPSILON ? "PICKUP_OFF_ROUTE"
                : location.progressKm() < 0 ? "PICKUP_BEHIND_VEHICLE" : "PICKUP_ON_ROUTE";
        return new RuleResult(1, code.equals("PICKUP_ON_ROUTE"), code, params);
    }

    static RuleResult deliveryRule(Context context) {
        List<RouteStop> stops = context.stops();
        int current = currentIndex(stops);
        if (current < 0) {
            return noStop(2);
        }
        if (current == stops.size() - 1) {
            return new RuleResult(2, true, "PICKUP_DELIVERY_IN_RANGE", Map.of());
        }
        List<Point> route = stops.subList(current, stops.size()).stream().map(RouteStop::location).toList();
        PickupRoutePath.Location location = PickupRoutePath.locate(route, context.request().delivery());
        int protectedIndex = protectedIndex(stops, current);
        double limitKm = protectedIndex < 0 ? Double.POSITIVE_INFINITY : location.vertexKm().get(protectedIndex - current);
        String code = location.progressKm() <= EPSILON ? "PICKUP_DELIVERY_NOT_AFTER_CURRENT"
                : location.progressKm() > limitKm + EPSILON ? "PICKUP_DELIVERY_BEYOND_PROTECTED" : "PICKUP_DELIVERY_IN_RANGE";
        Map<String, Object> params = protectedIndex < 0 ? Map.of()
                : params("protectedStopId", stops.get(protectedIndex).stopId());
        return new RuleResult(2, code.equals("PICKUP_DELIVERY_IN_RANGE"), code, params);
    }

    /**
     * Luật 9: giờ đến điểm giao, tính từ vị trí xe theo tuyến sau khi chèn (thời gian chạy từng chặng như màn Giám sát: chỉ đường của
     * Goong, không có thì đường nối thẳng ở 30 km/h), không muộn hơn hạn của yêu cầu.
     */
    static RuleResult deadlineRule(Context context) {
        Request request = context.request();
        Insertion insertion = insertion(context.stops(), request.delivery());
        if (insertion == null) {
            return noStop(9);
        }
        if (request.deadline() == null) {
            return new RuleResult(9, true, "PICKUP_NO_DEADLINE", Map.of());
        }
        List<Point> legs = new ArrayList<>();
        legs.add(context.position());
        List<RouteStop> stops = context.stops();
        int current = insertion.currentIndex();
        legs.add(stops.get(current).location());
        legs.add(request.pickup());
        int deliveryLeg;
        if (insertion.reusedIndex() < 0) {
            legs.add(request.delivery());
            deliveryLeg = legs.size() - 1;
        } else {
            for (int i = current + 1; i < insertion.reusedIndex(); i++) {
                if (!stops.get(i).completed()) {
                    legs.add(stops.get(i).location());
                }
            }
            legs.add(stops.get(insertion.reusedIndex()).location());
            deliveryLeg = legs.size() - 1;
        }
        LocalDateTime eta = context.at();
        for (int i = 1; i <= deliveryLeg; i++) {
            eta = eta.plusSeconds(Math.max(0, context.duration().applyAsLong(legs.get(i - 1), legs.get(i))));
        }
        boolean passed = DeadlineStatusRule.resolve(eta, request.deadline()) != DeadlineStatus.MISSED;
        return new RuleResult(9, passed, passed ? "PICKUP_DEADLINE_OK" : "PICKUP_DEADLINE_MISSED",
                params("eta", local(eta), "deadline", local(request.deadline())));
    }

    // --- luật 3–8, 10: hàng -----------------------------------------------------------------------

    static RuleResult payloadRule(Context context) {
        double pickupKg = context.request().packages().stream().mapToDouble(Cargo::weightKg).sum();
        double totalKg = context.onboardKg() + pickupKg;
        double max = context.vehicle().maxPayloadKg();
        boolean passed = totalKg <= max + EPSILON;
        return new RuleResult(3, passed, passed ? "PICKUP_PAYLOAD_OK" : "PICKUP_PAYLOAD_EXCEEDED",
                params("totalKg", round2(totalKg), "maxPayloadKg", round2(max), "overKg", passed ? 0.0 : round2(totalKg - max)));
    }

    static RuleResult freedSpaceRule(Context context) {
        FreedSpacePacker.Result packing = context.packing();
        long noRoom = packing.unplaced().stream().filter(item -> !"STACKING_VIOLATION".equals(item.reasonCode())).count();
        boolean passed = noRoom == 0;
        double pickupCm3 = context.request().packages().stream()
                .mapToDouble(cargo -> cargo.lengthCm() * cargo.widthCm() * cargo.heightCm()).sum();
        return new RuleResult(4, passed, passed ? "PICKUP_FREED_SPACE_OK" : "PICKUP_FREED_SPACE_INSUFFICIENT",
                params("pickupCm3", Math.round(pickupCm3), "freedCm3", Math.round(packing.freeVolumeCm3()),
                        "placedCount", packing.placements().size(), "totalCount", context.request().packages().size(),
                        "unplacedCount", noRoom));
    }

    static RuleResult axleRule(Context context) {
        AxleLoads loads = context.packing().after().axle();
        VehicleLimits vehicle = context.vehicle();
        if (loads == null || vehicle.frontAxleLimitKg() == null || vehicle.rearAxleLimitKg() == null) {
            return new RuleResult(5, true, "PICKUP_AXLE_UNAVAILABLE", params("reason", "NO_AXLES"));
        }
        double overFront = loads.frontKg() - vehicle.frontAxleLimitKg();
        double overRear = loads.rearKg() - vehicle.rearAxleLimitKg();
        boolean passed = overFront <= EPSILON && overRear <= EPSILON;
        return new RuleResult(5, passed, passed ? "PICKUP_AXLE_OK" : "PICKUP_AXLE_OVERLOAD",
                params("frontLoadKg", round2(loads.frontKg()), "rearLoadKg", round2(loads.rearKg()),
                        "overKg", round2(Math.max(0, Math.max(overFront, overRear)))));
    }

    /** Luật 6: chỉ kiểu lệch trọng tâm mới xuất hiện sau khi nhận làm luật không đạt (kiểu có từ trước: {@code PICKUP_COG_NOT_WORSE}). */
    static RuleResult centerOfGravityRule(Context context) {
        List<String> after = cogIssues(context.vehicle(), context.packing().after().centerOfGravity());
        Set<String> before = new LinkedHashSet<>(cogIssues(context.vehicle(), context.packing().before().centerOfGravity()));
        List<String> added = after.stream().filter(kind -> !before.contains(kind)).toList();
        String code = after.isEmpty() ? "PICKUP_COG_OK" : added.isEmpty() ? "PICKUP_COG_NOT_WORSE" : "PICKUP_COG_OFF_CENTER";
        List<String> shown = added.isEmpty() ? after : added;
        String reasons = String.join(",", shown.stream().map(kind -> kind.substring(0, kind.indexOf(':'))).distinct().toList());
        return new RuleResult(6, !code.equals("PICKUP_COG_OFF_CENTER"), code, params("reasons", reasons));
    }

    /** Kiểu lệch: mã kèm phía bị dồn về với lệch dọc ({@code COG_LONGITUDINAL:front}). */
    static List<String> cogIssues(VehicleLimits vehicle, CenterOfGravity center) {
        if (center == null) {
            return List.of();
        }
        double ratio = vehicle.maxCogOffsetRatio() != null && vehicle.maxCogOffsetRatio() > 0
                ? vehicle.maxCogOffsetRatio() : DEFAULT_MAX_COG_OFFSET_RATIO;
        List<String> issues = new ArrayList<>();
        if (Math.abs(center.y() - vehicle.widthCm() / 2) > ratio * vehicle.widthCm() + EPSILON) {
            issues.add("COG_LATERAL:");
        }
        double along = center.x() - vehicle.lengthCm() / 2;
        if (Math.abs(along) > ratio * vehicle.lengthCm() + EPSILON) {
            issues.add("COG_LONGITUDINAL:" + (along < 0 ? "front" : "rear"));
        }
        if (center.z() > vehicle.heightCm() * COG_HEIGHT_RATIO + EPSILON) {
            issues.add("COG_HIGH:");
        }
        return issues;
    }

    static RuleResult stackingRule(Context context) {
        long count = context.packing().unplaced().stream()
                .filter(item -> "STACKING_VIOLATION".equals(item.reasonCode())).count();
        boolean passed = count == 0;
        return new RuleResult(7, passed, passed ? "PICKUP_STACK_OK" : "PICKUP_FRAGILE_STACKED", params("count", count));
    }

    /** Luật 8: kiện nhận cùng loại hàng với loại đang khoá của chuyến, hoặc chuyến đã có lý do vượt luật. */
    static RuleResult handlingClassRule(Context context) {
        List<String> incoming = context.request().packages().stream()
                .map(cargo -> cargo.handlingClass() == null ? "STANDARD" : cargo.handlingClass()).toList();
        String locked = context.lockedClass() != null ? context.lockedClass()
                : incoming.isEmpty() ? "STANDARD" : incoming.getFirst();
        List<String> conflicts = incoming.stream().filter(value -> !value.equals(locked)).toList();
        Map<String, Object> params = params("lockedClass", locked, "conflictCount", conflicts.size(),
                "classes", String.join(",", new LinkedHashSet<>(conflicts)));
        if (conflicts.isEmpty()) {
            return new RuleResult(8, true, "PICKUP_CLASS_OK", params);
        }
        boolean overridden = context.overrideReason() != null && !context.overrideReason().isBlank();
        return new RuleResult(8, overridden, overridden ? "PICKUP_CLASS_OVERRIDDEN" : "PICKUP_CLASS_CONFLICT", params);
    }

    static RuleResult blockingRule(Context context) {
        int blocked = context.packing().blockedCount();
        return new RuleResult(10, blocked == 0, blocked == 0 ? "PICKUP_NOT_BLOCKING" : "PICKUP_BLOCKS_CARGO",
                params("blockedCount", blocked));
    }

    // ---------------------------------------------------------------------------------------------

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** Giờ Việt Nam không múi giờ, như mọi mốc giờ khác của máy chủ. */
    private static String local(LocalDateTime value) {
        return value.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
