package fu.se184491.loadmaster_be.service.route;

import fu.se184491.loadmaster_be.dto.goong.DirectionsResponse;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Chọn polyline cho một chặng: A lấy đường ngắn nhất (thời gian), B lấy phương án thay thế dài hơn một chút
 * (giờ cao điểm Việt Nam ưu tiên rõ hơn). Không đổi thứ tự điểm.
 */
public final class RoutePathSelector {

    private static final LocalTime MORNING_FROM = LocalTime.of(7, 0);
    private static final LocalTime MORNING_TO = LocalTime.of(9, 0);
    private static final LocalTime EVENING_FROM = LocalTime.of(16, 30);
    private static final LocalTime EVENING_TO = LocalTime.of(19, 30);
    /** B không được dài quá 1,5 lần A. */
    private static final double MAX_QUIETER_RATIO = 1.5;
    /** Ngoài giờ cao điểm: B phải chậm hơn A ít nhất 5 % thì mới đổi. */
    private static final double MIN_QUIETER_RATIO = 1.05;

    private RoutePathSelector() {
    }

    public static DirectionsResponse.Route shortest(DirectionsResponse response) {
        return response.getRoutes().stream()
                .min(Comparator.comparingLong(DirectionsResponse.Route::getDurationSeconds)
                        .thenComparingLong(DirectionsResponse.Route::getDistanceMeters))
                .orElseThrow();
    }

    /**
     * Phương án B: cùng cặp điểm, polyline khác A nếu Goong trả alternatives. Giờ cao điểm (7–9, 16:30–19:30)
     * lấy đường chậm hơn (trong trần 1,5×) vì đường ngắn thường kẹt.
     */
    public static DirectionsResponse.Route quieter(
            DirectionsResponse response,
            DirectionsResponse.Route shortest,
            LocalDateTime departure
    ) {
        List<DirectionsResponse.Route> routes = response.getRoutes();
        if (routes == null || routes.size() < 2 || shortest == null) {
            return shortest;
        }
        long cap = Math.round(shortest.getDurationSeconds() * MAX_QUIETER_RATIO);
        boolean rush = isVietnamRushHour(departure);
        DirectionsResponse.Route picked = null;
        for (DirectionsResponse.Route route : routes) {
            if (samePolyline(route, shortest)) {
                continue;
            }
            long duration = route.getDurationSeconds();
            if (duration > cap) {
                continue;
            }
            if (!rush && duration < Math.round(shortest.getDurationSeconds() * MIN_QUIETER_RATIO)) {
                continue;
            }
            if (picked == null) {
                picked = route;
                continue;
            }
            if (rush) {
                if (duration > picked.getDurationSeconds()) {
                    picked = route;
                }
            } else if (duration < picked.getDurationSeconds()) {
                picked = route;
            }
        }
        return picked == null ? shortest : picked;
    }

    static boolean isVietnamRushHour(LocalDateTime departure) {
        if (departure == null) {
            return false;
        }
        LocalTime time = departure.toLocalTime();
        return !time.isBefore(MORNING_FROM) && time.isBefore(MORNING_TO)
                || !time.isBefore(EVENING_FROM) && !time.isAfter(EVENING_TO);
    }

    private static boolean samePolyline(DirectionsResponse.Route a, DirectionsResponse.Route b) {
        return Objects.equals(a.getPolyline(), b.getPolyline())
                && a.getDurationSeconds() == b.getDurationSeconds()
                && a.getDistanceMeters() == b.getDistanceMeters();
    }
}
