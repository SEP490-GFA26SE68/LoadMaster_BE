package fu.se184491.loadmaster_be.service.optimization.manual;

import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Box;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Vùng theo điểm giao trong lòng thùng, thuần, cùng công thức với {@code StopZoneCalculator} của dịch vụ tối ưu: chiều dài vùng
 * tỷ lệ với thể tích hàng của điểm trên phần dài còn lại sau các khoảng đệm 10 cm; điểm giao trước nằm sát cửa sau (X lớn), điểm
 * giao sau sâu nhất (X = 0); mốc cuối đặt đúng chiều dài lòng thùng. Đơn vị cm.
 *
 * <p>Số lần dỡ-xếp lại của một cách xếp: số kiện có <b>tâm theo X</b> nằm trong vùng khác vùng của điểm giao mình. Tâm rơi vào
 * khoảng đệm thuộc vùng gần hơn; cách đều hai vùng thì vùng sâu hơn.</p>
 */
public final class StopZoneLayout {

    /** Khoảng đệm giữa hai vùng liền nhau, cm (như {@code ACCESSIBILITY_BUFFER_CM}). */
    static final double BUFFER_CM = 10.0;
    private static final double EPSILON = 1e-6;

    private StopZoneLayout() {
    }

    /** Một điểm giao của chuyến: mã, thứ tự giao, tổng thể tích hàng (cm³) của mọi kiện của điểm trong lần tối ưu. */
    public record StopLoad(Long stopId, int sequence, double volumeCm3) {
    }

    public record Zone(Long stopId, double startX, double endX) {
    }

    /** Các vùng theo thứ tự {@code stops}; không có điểm nào: danh sách rỗng. */
    public static List<Zone> zones(double innerLength, List<StopLoad> stops) {
        if (stops.isEmpty()) {
            return List.of();
        }
        double usable = Math.max(0, innerLength - (stops.size() - 1) * BUFFER_CM);
        double total = stops.stream().mapToDouble(StopLoad::volumeCm3).sum();
        List<Integer> deepestToDoor = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            deepestToDoor.add(i);
        }
        // Giao trước sát cửa: sắp theo thứ tự giao (giữ thứ tự đầu vào khi trùng), rồi đảo để đi từ vách trong ra cửa.
        deepestToDoor.sort(Comparator.<Integer>comparingInt(i -> stops.get(i).sequence()).thenComparingInt(i -> i));
        Collections.reverse(deepestToDoor);
        double[] start = new double[stops.size()];
        double[] end = new double[stops.size()];
        double cursor = 0;
        for (int position = 0; position < deepestToDoor.size(); position++) {
            int index = deepestToDoor.get(position);
            double ratio = total > 0 ? stops.get(index).volumeCm3() / total : 1.0 / stops.size();
            double length = usable * ratio;
            start[index] = round4(cursor);
            end[index] = round4(cursor + length);
            cursor += length;
            if (position < deepestToDoor.size() - 1) {
                cursor += BUFFER_CM;
            }
        }
        end[deepestToDoor.get(deepestToDoor.size() - 1)] = round4(innerLength);
        List<Zone> zones = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            zones.add(new Zone(stops.get(i).stopId(), start[i], end[i]));
        }
        return zones;
    }

    /** Vùng chứa toạ độ X (cm); trong khoảng đệm là vùng gần hơn, cách đều thì vùng sâu hơn. Không có vùng: null. */
    public static Zone zoneAt(List<Zone> zones, double x) {
        Zone nearest = null;
        double nearestGap = Double.POSITIVE_INFINITY;
        for (Zone zone : zones) {
            double gap = Math.max(Math.max(zone.startX() - x, x - zone.endX()), 0);
            boolean closer = gap < nearestGap - EPSILON;
            boolean tieDeeper = Math.abs(gap - nearestGap) <= EPSILON && nearest != null && zone.startX() < nearest.startX() - EPSILON;
            if (closer || tieDeeper) {
                nearest = zone;
                nearestGap = gap;
            }
        }
        return nearest;
    }

    /** Số kiện nằm ngoài vùng của điểm giao mình; {@code stopByBoxId} cho điểm giao của từng kiện. */
    public static int rehandlingCount(List<Zone> zones, Collection<Box> boxes, Map<Long, Long> stopByBoxId) {
        int count = 0;
        for (Box box : boxes) {
            Zone zone = zoneAt(zones, box.x() + box.length() / 2.0);
            if (zone != null && !zone.stopId().equals(stopByBoxId.get(box.id()))) {
                count++;
            }
        }
        return count;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
