package fu.se184491.loadmaster_be.service.trip;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Luật đổi thứ tự điểm giao khi xe đang chạy, thuần (không đọc kho): thứ tự đề xuất có hợp lệ không, và các kiện còn trên xe có còn dỡ
 * được theo thứ tự mới không. Cùng luật với web ({@code checkProposedOrder}, {@code checkStopReorder}).
 */
public final class StopReorderRules {

    /** Mặt sau bị che từ tỷ lệ này trở lên là che kín (nhiễu dấu phẩy động vẫn là che kín). */
    static final double FULL_COVER_RATIO = 0.9999;
    private static final double EPSILON = 1e-6;

    private StopReorderRules() {
    }

    public record PickupPair(Long pickupStopId, Long deliveryStopId) {
    }

    /** Lỗi thứ tự: {@code code} là tên mã lỗi, {@code stopIds} là các điểm liên quan. */
    public record Violation(String code, List<Long> stopIds) {
    }

    /**
     * Lỗi đầu tiên của thứ tự đề xuất, theo thứ tự: không phải hoán vị (hoặc không đổi gì) → điểm cố định bị dời → điểm nhận đứng sau
     * điểm giao của nó; null khi hợp lệ. {@code fixedCount}: số điểm đầu danh sách phải đứng nguyên (đã hoàn tất hoặc xe đã tới).
     */
    public static Violation checkProposedOrder(List<Long> current, List<Long> proposed, int fixedCount, List<PickupPair> pickups) {
        boolean sameSet = proposed.size() == current.size()
                && new HashSet<>(proposed).size() == current.size()
                && current.containsAll(proposed);
        if (!sameSet || proposed.equals(current)) {
            return new Violation("STOP_ORDER_INVALID", List.of());
        }
        List<Long> moved = new ArrayList<>();
        for (int index = 0; index < Math.min(fixedCount, current.size()); index++) {
            if (!current.get(index).equals(proposed.get(index))) {
                moved.add(current.get(index));
            }
        }
        if (!moved.isEmpty()) {
            return new Violation("STOP_NOT_MOVABLE", moved);
        }
        for (PickupPair pair : pickups) {
            int pickupIndex = proposed.indexOf(pair.pickupStopId());
            int deliveryIndex = proposed.indexOf(pair.deliveryStopId());
            if (pickupIndex >= 0 && deliveryIndex >= 0 && pickupIndex > deliveryIndex) {
                return new Violation("PICKUP_AFTER_DELIVERY", List.of(pair.pickupStopId(), pair.deliveryStopId()));
            }
        }
        return null;
    }

    /**
     * Một kiện còn phải dỡ: khoá duy nhất, mã hiện cho người đọc, điểm giao và hộp đã xếp (cm, cửa sau ở X lớn).
     */
    public record Cargo(String key, String label, Long stopId, double x, double y, double z, double length, double width,
                        double height) {
    }

    /** Kết quả kiểm khả năng dỡ: kiện bị che kín mặt sau và kiện bị che một phần, theo khoá. */
    public record Unloading(Set<String> blocked, Set<String> partial) {
    }

    /**
     * Với thứ tự {@code order} (mã điểm, điểm đầu giao trước): kiện nào bị kiện giao muộn hơn nằm hẳn giữa nó và cửa sau che kín hay che
     * một phần mặt sau.
     */
    public static Unloading checkUnloading(List<Cargo> cargo, List<Long> order) {
        Set<String> blocked = new HashSet<>();
        Set<String> partial = new HashSet<>();
        for (Cargo target : cargo) {
            int rank = order.indexOf(target.stopId());
            double rear = target.x() + target.length();
            List<double[]> covers = new ArrayList<>();
            for (Cargo other : cargo) {
                if (other == target || order.indexOf(other.stopId()) <= rank || other.x() < rear - EPSILON) {
                    continue;
                }
                double y0 = Math.max(target.y(), other.y());
                double y1 = Math.min(target.y() + target.width(), other.y() + other.width());
                double z0 = Math.max(target.z(), other.z());
                double z1 = Math.min(target.z() + target.height(), other.z() + other.height());
                if (y1 - y0 > EPSILON && z1 - z0 > EPSILON) {
                    covers.add(new double[]{y0, y1, z0, z1});
                }
            }
            if (covers.isEmpty()) {
                continue;
            }
            double ratio = coveredArea(covers) / (target.width() * target.height());
            if (ratio >= FULL_COVER_RATIO) {
                blocked.add(target.key());
            } else if (ratio > EPSILON) {
                partial.add(target.key());
            }
        }
        return new Unloading(blocked, partial);
    }

    /** Diện tích hợp của các hình chữ nhật YZ (nén toạ độ). */
    private static double coveredArea(List<double[]> covers) {
        TreeSet<Double> yEdges = new TreeSet<>();
        TreeSet<Double> zEdges = new TreeSet<>();
        for (double[] rect : covers) {
            yEdges.add(rect[0]);
            yEdges.add(rect[1]);
            zEdges.add(rect[2]);
            zEdges.add(rect[3]);
        }
        Double[] ys = yEdges.toArray(new Double[0]);
        Double[] zs = zEdges.toArray(new Double[0]);
        double area = 0;
        for (int i = 0; i + 1 < ys.length; i++) {
            for (int j = 0; j + 1 < zs.length; j++) {
                double cy = (ys[i] + ys[i + 1]) / 2;
                double cz = (zs[j] + zs[j + 1]) / 2;
                for (double[] rect : covers) {
                    if (cy > rect[0] && cy < rect[1] && cz > rect[2] && cz < rect[3]) {
                        area += (ys[i + 1] - ys[i]) * (zs[j + 1] - zs[j]);
                        break;
                    }
                }
            }
        }
        return area;
    }
}
