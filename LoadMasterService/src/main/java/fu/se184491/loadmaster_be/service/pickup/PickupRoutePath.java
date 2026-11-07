package fu.se184491.loadmaster_be.service.pickup;

import java.util.ArrayList;
import java.util.List;

/**
 * Hình học tuyến của luật nhận hàng dọc đường, thuần. Toạ độ WGS84 (độ), khoảng cách km.
 */
public final class PickupRoutePath {

    static final double EARTH_RADIUS_KM = 6371.0;
    private static final double KM_PER_DEGREE = EARTH_RADIUS_KM * Math.PI / 180;
    /** Đoạn ngắn hơn chừng này là một điểm: không có hướng để chiếu lên. */
    private static final double DEGENERATE_KM = 1e-9;

    private PickupRoutePath() {
    }

    public record Point(double lat, double lng) {
    }

    /**
     * Vị trí của một điểm so với đường gấp khúc: khoảng cách tới đường, tiến độ dọc đường của chân đường vuông góc (hai đoạn đầu cuối
     * kéo dài: trước đỉnh đầu là âm), và tiến độ của từng đỉnh trong cùng phép chiếu.
     */
    public record Location(double distanceKm, double progressKm, List<Double> vertexKm) {
    }

    public static double haversineKm(Point a, Point b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    /** Chiếu phẳng quanh {@code point} (kinh độ nhân cos vĩ độ): đủ chính xác ở cỡ một tuyến nội vùng. {@code path} có ít nhất một đỉnh. */
    public static Location locate(List<Point> path, Point point) {
        double cosLat = Math.cos(Math.toRadians(point.lat()));
        int n = path.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = (path.get(i).lng() - point.lng()) * cosLat * KM_PER_DEGREE;
            ys[i] = (path.get(i).lat() - point.lat()) * KM_PER_DEGREE;
        }
        List<Double> vertexKm = new ArrayList<>();
        vertexKm.add(0.0);
        List<Integer> usable = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            double length = Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
            vertexKm.add(vertexKm.get(i - 1) + length);
            if (length > DEGENERATE_KM) {
                usable.add(i - 1);
            }
        }
        if (usable.isEmpty()) {
            return new Location(Math.hypot(xs[0], ys[0]), 0, vertexKm);
        }
        int first = usable.get(0);
        int last = usable.get(usable.size() - 1);
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestProgress = 0;
        for (int index : usable) {
            double sx = xs[index];
            double sy = ys[index];
            double dx = xs[index + 1] - sx;
            double dy = ys[index + 1] - sy;
            double length = Math.hypot(dx, dy);
            double t = (-sx * dx - sy * dy) / (length * length);
            double clamped = Math.min(1, Math.max(0, t));
            double distance = Math.hypot(sx + clamped * dx, sy + clamped * dy);
            double along;
            if (index == first && index == last) {
                along = t;
            } else if (index == first) {
                along = Math.min(t, 1);
            } else if (index == last) {
                along = Math.max(t, 0);
            } else {
                along = clamped;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                bestProgress = vertexKm.get(index) + along * length;
            }
        }
        return new Location(bestDistance, bestProgress, vertexKm);
    }
}
