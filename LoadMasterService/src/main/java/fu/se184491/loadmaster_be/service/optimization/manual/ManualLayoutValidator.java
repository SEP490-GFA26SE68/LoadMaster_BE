package fu.se184491.loadmaster_be.service.optimization.manual;

import fu.se184491.loadmaster_be.exception.ErrorCode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Kiểm một cách xếp đã chỉnh tay, thuần (không truy cập dữ liệu). Đơn vị: cm, kg.
 *
 * <p>Gốc toạ độ ở góc vách trước - trái - sàn, X dọc thùng về phía cửa sau. Chỉ kiện đã dời và các kiện
 * bị ảnh hưởng (kiện đang tựa lên kiện đã dời, trước hoặc sau khi dời) được kiểm: phần còn lại là kết quả
 * của bộ tối ưu, đã kiểm bằng ngưỡng của nó.</p>
 */
public final class ManualLayoutValidator {

    /** Dung sai so số thực, cm. Toạ độ lưu hai chữ số lẻ. */
    static final double EPSILON = 0.01;
    /** Mặt dưới của kiện cách mặt trên chỗ đỡ tối đa chừng này vẫn coi là tựa lên (như trọng lực khi chỉnh tay). */
    static final double CONTACT_TOLERANCE = 0.5;
    /** Diện tích tựa tối thiểu, như HC-05 của dịch vụ tối ưu và mặc định của app. */
    static final double MIN_SUPPORT_RATIO = 0.7;

    /** Mã hướng của dịch vụ tối ưu: chữ thứ i là cạnh gốc nằm theo trục X, Y, Z. */
    private static final char[][] ORIENTATIONS = {
            {'L', 'W', 'H'}, {'W', 'L', 'H'}, {'L', 'H', 'W'},
            {'W', 'H', 'L'}, {'H', 'L', 'W'}, {'H', 'W', 'L'},
    };

    private ManualLayoutValidator() {
    }

    public record Box(Long id, double x, double y, double z, double length, double width, double height,
                      double weightKg) {
        double top() {
            return z + height;
        }
    }

    public record Obstacle(double x, double y, double z, double length, double width, double height,
                           boolean loadBearing) {
        Box asBox() {
            return new Box(null, x, y, z, length, width, height, 0);
        }
    }

    public record Space(double length, double width, double height, List<Obstacle> obstacles) {
    }

    /** Vị trí trục và tải của xe hai trục (kg); thiếu một số thì không kiểm tải trục. */
    public record AxleConfig(Double frontX, Double rearX, Double frontLimitKg, Double rearLimitKg,
                             Double emptyFrontKg, Double emptyRearKg) {
        boolean complete() {
            return frontX != null && rearX != null && frontLimitKg != null && rearLimitKg != null
                    && emptyFrontKg != null && emptyRearKg != null && rearX - frontX > 0
                    && frontLimitKg > 0 && rearLimitKg > 0;
        }
    }

    public record AxleLoads(double frontKg, double rearKg) {
    }

    public record CenterOfGravity(double x, double y, double z) {
    }

    /** Kích thước sau khi áp mã hướng {@code rotation} lên kích thước gốc (dài, rộng, cao). */
    public static double[] orientedSize(double[] base, int rotation) {
        char[] axes = ORIENTATIONS[rotation];
        double[] size = new double[3];
        for (int i = 0; i < 3; i++) {
            size[i] = base[indexOf(axes[i])];
        }
        return size;
    }

    /** Kích thước gốc (dài, rộng, cao) từ kích thước đã xoay theo mã {@code rotation}. */
    public static double[] baseSize(double[] packed, int rotation) {
        char[] axes = ORIENTATIONS[rotation];
        double[] base = new double[3];
        for (int i = 0; i < 3; i++) {
            base[indexOf(axes[i])] = packed[i];
        }
        return base;
    }

    /**
     * Các mã hướng một kiện được nhận, như dịch vụ tối ưu đọc loại kiện: công tắc tổng tắt hoặc không có loại kiện thì
     * chỉ hướng gốc; bật thì mọi hướng sinh ra từ các phép xoay 90° được phép quanh X, Y, Z.
     */
    public static Set<Integer> allowedRotations(boolean rotationEnabled, boolean allowX, boolean allowY,
                                                boolean allowZ) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> pending = new ArrayList<>();
        seen.add("LWH");
        pending.add("LWH");
        if (rotationEnabled) {
            while (!pending.isEmpty()) {
                String current = pending.remove(pending.size() - 1);
                List<String> next = new ArrayList<>();
                if (allowX) {
                    next.add("" + current.charAt(0) + current.charAt(2) + current.charAt(1));
                }
                if (allowY) {
                    next.add("" + current.charAt(2) + current.charAt(1) + current.charAt(0));
                }
                if (allowZ) {
                    next.add("" + current.charAt(1) + current.charAt(0) + current.charAt(2));
                }
                for (String candidate : next) {
                    if (seen.add(candidate)) {
                        pending.add(candidate);
                    }
                }
            }
        }
        Set<Integer> codes = new HashSet<>();
        for (String orientation : seen) {
            for (int code = 0; code < ORIENTATIONS.length; code++) {
                if (new String(ORIENTATIONS[code]).equals(orientation)) {
                    codes.add(code);
                }
            }
        }
        return codes;
    }

    /**
     * Lỗi đầu tiên của cách xếp mới, hoặc {@code null} khi hợp lệ.
     *
     * @param before cách xếp của phương án nguồn (để biết kiện nào đang tựa lên kiện đã dời)
     * @param after  cách xếp sau khi áp bản chỉnh, cùng tập kiện
     * @param edited mã các kiện đã đổi vị trí hoặc hướng
     */
    public static ErrorCode firstViolation(Space space, List<Box> before, List<Box> after, Set<Long> edited,
                                           AxleConfig axles) {
        List<Box> moved = after.stream().filter(box -> edited.contains(box.id())).toList();
        for (Box box : moved) {
            if (outOfBounds(box, space)) {
                return ErrorCode.MANUAL_EDIT_OUT_OF_BOUNDS;
            }
        }
        for (Box box : moved) {
            for (Obstacle obstacle : space.obstacles()) {
                if (overlaps(box, obstacle.asBox())) {
                    return ErrorCode.MANUAL_EDIT_OVERLAP;
                }
            }
            for (Box other : after) {
                if (!other.id().equals(box.id()) && overlaps(box, other)) {
                    return ErrorCode.MANUAL_EDIT_OVERLAP;
                }
            }
        }
        Set<Long> affected = new HashSet<>(edited);
        affected.addAll(restingOn(before, edited));
        affected.addAll(restingOn(after, edited));
        for (Box box : after) {
            if (affected.contains(box.id()) && supportRatio(box, after, space.obstacles()) < MIN_SUPPORT_RATIO - 1e-6) {
                return ErrorCode.MANUAL_EDIT_UNSUPPORTED;
            }
        }
        AxleLoads loads = axleLoads(after, axles);
        if (loads != null && (loads.frontKg() > axles.frontLimitKg() + EPSILON
                || loads.rearKg() > axles.rearLimitKg() + EPSILON)) {
            return ErrorCode.MANUAL_EDIT_AXLE_OVERLOAD;
        }
        return null;
    }

    /** Tải trục trước / sau theo mô hình đòn bẩy của dịch vụ tối ưu (HC-12); xe thiếu dữ liệu trục: null. */
    public static AxleLoads axleLoads(Collection<Box> boxes, AxleConfig axles) {
        if (axles == null || !axles.complete()) {
            return null;
        }
        double wheelbase = axles.rearX() - axles.frontX();
        double total = 0;
        double moment = 0;
        for (Box box : boxes) {
            total += box.weightKg();
            moment += box.weightKg() * (box.x() + box.length() / 2.0 - axles.frontX());
        }
        double rear = moment / wheelbase;
        return new AxleLoads(axles.emptyFrontKg() + total - rear, axles.emptyRearKg() + rear);
    }

    /** Tâm khối lượng của hàng; không có hàng (hoặc tổng khối lượng 0): null. */
    public static CenterOfGravity centerOfGravity(Collection<Box> boxes) {
        double total = 0;
        double x = 0;
        double y = 0;
        double z = 0;
        for (Box box : boxes) {
            total += box.weightKg();
            x += box.weightKg() * (box.x() + box.length() / 2.0);
            y += box.weightKg() * (box.y() + box.width() / 2.0);
            z += box.weightKg() * (box.z() + box.height() / 2.0);
        }
        if (total <= 0) {
            return null;
        }
        return new CenterOfGravity(x / total, y / total, z / total);
    }

    static double supportRatio(Box box, List<Box> layout, List<Obstacle> obstacles) {
        if (box.z() <= CONTACT_TOLERANCE) {
            return 1.0;
        }
        double base = box.length() * box.width();
        if (base <= 0) {
            return 0;
        }
        double supported = 0;
        for (Box other : layout) {
            if (!other.id().equals(box.id()) && touchesTop(box, other)) {
                supported += footprintOverlap(box, other);
            }
        }
        for (Obstacle obstacle : obstacles) {
            Box top = obstacle.asBox();
            if (obstacle.loadBearing() && touchesTop(box, top)) {
                supported += footprintOverlap(box, top);
            }
        }
        return Math.min(1.0, supported / base);
    }

    /** Kiện (không thuộc {@code edited}) có mặt dưới tựa lên một kiện của {@code edited}. */
    private static Set<Long> restingOn(List<Box> layout, Set<Long> edited) {
        Set<Long> result = new HashSet<>();
        for (Box upper : layout) {
            if (edited.contains(upper.id())) {
                continue;
            }
            for (Box lower : layout) {
                if (edited.contains(lower.id()) && touchesTop(upper, lower) && footprintOverlap(upper, lower) > 0) {
                    result.add(upper.id());
                }
            }
        }
        return result;
    }

    private static boolean touchesTop(Box upper, Box lower) {
        return Math.abs(upper.z() - lower.top()) <= CONTACT_TOLERANCE;
    }

    private static double footprintOverlap(Box a, Box b) {
        double dx = Math.min(a.x() + a.length(), b.x() + b.length()) - Math.max(a.x(), b.x());
        double dy = Math.min(a.y() + a.width(), b.y() + b.width()) - Math.max(a.y(), b.y());
        return dx > EPSILON && dy > EPSILON ? dx * dy : 0;
    }

    private static boolean overlaps(Box a, Box b) {
        return Math.min(a.x() + a.length(), b.x() + b.length()) - Math.max(a.x(), b.x()) > EPSILON
                && Math.min(a.y() + a.width(), b.y() + b.width()) - Math.max(a.y(), b.y()) > EPSILON
                && Math.min(a.top(), b.top()) - Math.max(a.z(), b.z()) > EPSILON;
    }

    private static boolean outOfBounds(Box box, Space space) {
        return box.x() < -EPSILON || box.y() < -EPSILON || box.z() < -EPSILON
                || box.x() + box.length() > space.length() + EPSILON
                || box.y() + box.width() > space.width() + EPSILON
                || box.top() > space.height() + EPSILON;
    }

    private static int indexOf(char axis) {
        return switch (axis) {
            case 'L' -> 0;
            case 'W' -> 1;
            default -> 2;
        };
    }
}
