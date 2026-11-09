package fu.se184491.loadmaster_be.service.pickup;

import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.AxleConfig;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.AxleLoads;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Box;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.CenterOfGravity;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Obstacle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Xếp kiện nhận dọc đường vào phần thùng đang trống, <b>không dời kiện nào đang chở</b>. Thuần, tất định; cm, kg.
 *
 * <p>Phần trống là lòng thùng trừ kiện còn trên xe lúc kiện nhận lên và vật cản của xe. Kiện nhận xếp theo diện tích đáy rồi thể
 * tích giảm dần; mỗi kiện thử các điểm ứng viên — thấp trước, rồi sát cửa trước (kiện nhận giao sớm hơn kiện còn chở, đặt gần cửa thì
 * không bị chắn lối dỡ), rồi theo Y — với từng hướng đặt qua được cửa, và nhận chỗ hợp lệ đầu tiên. Ở trên cao, kiện nhận chỉ tựa lên
 * kiện nhận khác cho xếp chồng (không dễ vỡ, tải đè tối đa ba lần khối lượng của kiện bên dưới, diện tích tựa tối thiểu 0,7) — không
 * bao giờ tựa lên kiện đang chở.</p>
 *
 * <p>Lý do kiện không xếp được dùng mã của hợp đồng tối ưu: {@code OVER_PAYLOAD}, {@code DOOR_TOO_SMALL}, {@code NO_SPACE},
 * {@code STACKING_VIOLATION} (chỉ còn chỗ đè lên kiện không cho xếp chồng).</p>
 */
public final class FreedSpacePacker {

    static final double EPSILON = 0.01;
    static final double MIN_SUPPORT_RATIO = 0.7;
    /** Kiện nhận không có loại kiện: chịu tải đè tối đa chừng này lần khối lượng của chính nó (như mặc định của app). */
    static final double TOP_LOAD_FACTOR = 3.0;
    /** Mặt sau bị che từ chừng này trở lên là bị chắn kín (LIFO). */
    static final double FULL_COVER_RATIO = 0.9999;

    private FreedSpacePacker() {
    }

    /** Lòng thùng, cửa sau (null: bằng mặt cắt lòng thùng), vật cản và trục của xe. */
    public record Vehicle(double length, double width, double height, Double doorWidth, Double doorHeight,
                          List<Obstacle> obstacles, AxleConfig axles) {
    }

    /** Kiện còn trên xe lúc kiện nhận lên: hộp đã xếp và thứ hạng giao (lớn hơn là giao muộn hơn). */
    public record Onboard(Box box, double deliveryRank) {
    }

    /** Một kiện nhận (cm, kg); {@code fragile}: không cho xếp gì lên, chỉ quay quanh trục đứng. */
    public record Item(int index, double length, double width, double height, double weightKg, boolean fragile) {
    }

    public record Placement(int index, int rotation, double x, double y, double z, double length, double width,
                            double height) {
    }

    public record Unplaced(int index, String reasonCode) {
    }

    /** Tổng khối lượng, trọng tâm (null khi không có hàng) và tải trục (null khi xe thiếu dữ liệu trục) của một khối hàng. */
    public record Snapshot(double totalKg, CenterOfGravity centerOfGravity, AxleLoads axle) {
    }

    public record Result(List<Placement> placements, List<Unplaced> unplaced, Snapshot before, Snapshot after,
                         int blockedCount, double freeVolumeCm3) {
    }

    private record Size(double length, double width, double height, int rotation) {
    }

    private static final class Placed {
        final Item item;
        final Box box;
        final int rotation;
        final List<Placed> supports;
        double topKg;

        Placed(Item item, Box box, int rotation, List<Placed> supports) {
            this.item = item;
            this.box = box;
            this.rotation = rotation;
            this.supports = supports;
        }
    }

    private record Attempt(Placed found, boolean stackingRejected) {
    }

    /**
     * @param payloadAvailableKg tải trọng còn nhận được (tải tối đa trừ hàng đang chở)
     * @param pickupRank         thứ hạng giao của kiện nhận, cùng thang với {@link Onboard#deliveryRank()}
     */
    public static Result pack(Vehicle vehicle, List<Onboard> onboard, List<Item> items, double payloadAvailableKg,
                              double pickupRank) {
        List<Box> fixed = new ArrayList<>();
        onboard.forEach(item -> fixed.add(item.box()));
        for (Obstacle obstacle : vehicle.obstacles()) {
            fixed.add(new Box(null, obstacle.x(), obstacle.y(), obstacle.z(), obstacle.length(), obstacle.width(),
                    obstacle.height(), 0));
        }
        List<Box> onboardBoxes = onboard.stream().map(Onboard::box).toList();
        Snapshot before = snapshot(onboardBoxes, vehicle.axles());

        List<Item> order = new ArrayList<>(items);
        order.sort(Comparator.<Item>comparingDouble(item -> -item.length() * item.width())
                .thenComparingDouble(item -> -item.length() * item.width() * item.height())
                .thenComparingInt(Item::index));

        List<Placed> placed = new ArrayList<>();
        List<Unplaced> unplaced = new ArrayList<>();
        double remainingKg = payloadAvailableKg;
        for (Item item : order) {
            List<Size> sizes = doorSizes(vehicle, item);
            if (sizes.isEmpty()) {
                unplaced.add(new Unplaced(item.index(), "DOOR_TOO_SMALL"));
                continue;
            }
            if (item.weightKg() > remainingKg + EPSILON) {
                unplaced.add(new Unplaced(item.index(), "OVER_PAYLOAD"));
                continue;
            }
            Attempt attempt = place(vehicle, fixed, placed, item, sizes);
            if (attempt.found() == null) {
                unplaced.add(new Unplaced(item.index(), attempt.stackingRejected() ? "STACKING_VIOLATION" : "NO_SPACE"));
                continue;
            }
            Placed record = attempt.found();
            placed.add(record);
            remainingKg -= item.weightKg();
            passLoad(record.supports, item.weightKg(), new HashSet<>());
        }

        List<Placement> placements = new ArrayList<>();
        for (Placed record : placed) {
            Box box = record.box;
            placements.add(new Placement(record.item.index(), record.rotation, box.x(), box.y(), box.z(),
                    box.length(), box.width(), box.height()));
        }
        placements.sort(Comparator.comparingInt(Placement::index));
        unplaced.sort(Comparator.comparingInt(Unplaced::index));

        List<Box> all = new ArrayList<>(onboardBoxes);
        placed.forEach(record -> all.add(record.box));
        Snapshot after = snapshot(all, vehicle.axles());
        int blocked = blockedCount(onboard, placed.stream().map(record -> record.box).toList(), pickupRank);
        return new Result(placements, unplaced, before, after, blocked, freeVolume(vehicle, fixed));
    }

    // ---------------------------------------------------------------------------------------------

    /** Chỗ tốt nhất qua mọi hướng: thấp trước, rồi mép phía cửa (x + dài) xa nhất, rồi y nhỏ nhất. */
    private static Attempt place(Vehicle vehicle, List<Box> fixed, List<Placed> placed, Item item, List<Size> sizes) {
        boolean stackingRejected = false;
        Placed best = null;
        for (Size size : sizes) {
            Attempt attempt = placeOriented(vehicle, fixed, placed, item, size);
            stackingRejected |= attempt.stackingRejected();
            Placed found = attempt.found();
            if (found != null && (best == null || better(found.box, best.box))) {
                best = found;
            }
        }
        return new Attempt(best, best == null && stackingRejected);
    }

    private static boolean better(Box a, Box b) {
        if (Math.abs(a.z() - b.z()) > EPSILON) {
            return a.z() < b.z();
        }
        double rearA = a.x() + a.length();
        double rearB = b.x() + b.length();
        if (Math.abs(rearA - rearB) > EPSILON) {
            return rearA > rearB;
        }
        return a.y() < b.y() - EPSILON;
    }

    private static Attempt placeOriented(Vehicle vehicle, List<Box> fixed, List<Placed> placed, Item item, Size size) {
        double l = size.length();
        double w = size.width();
        double h = size.height();
        if (l > vehicle.length() + EPSILON || w > vehicle.width() + EPSILON || h > vehicle.height() + EPSILON) {
            return new Attempt(null, false);
        }
        List<Box> blockers = new ArrayList<>(fixed);
        placed.forEach(record -> blockers.add(record.box));

        TreeSet<Double> zs = new TreeSet<>();
        zs.add(0.0);
        placed.forEach(record -> zs.add(round(top(record.box))));
        boolean stackingRejected = false;
        for (double z : zs) {
            if (z + h > vehicle.height() + EPSILON) {
                break;
            }
            List<Box> layer = blockers.stream()
                    .filter(box -> box.z() < z + h - EPSILON && top(box) > z + EPSILON).toList();
            List<Double> xs = new ArrayList<>(xCandidates(vehicle, layer, placed, z, l));
            xs.sort(Comparator.reverseOrder());
            List<Double> ys = new ArrayList<>(yCandidates(vehicle, layer, placed, z, w));
            ys.sort(Comparator.naturalOrder());
            for (double x : xs) {
                List<Box> column = layer.stream()
                        .filter(box -> box.x() < x + l - EPSILON && box.x() + box.length() > x + EPSILON).toList();
                for (double y : ys) {
                    if (column.stream().anyMatch(box -> box.y() < y + w - EPSILON && box.y() + box.width() > y + EPSILON)) {
                        continue;
                    }
                    Box candidate = new Box(null, x, y, z, l, w, h, item.weightKg());
                    if (z <= EPSILON) {
                        return new Attempt(new Placed(item, candidate, size.rotation(), List.of()), stackingRejected);
                    }
                    List<Placed> lowers = new ArrayList<>();
                    double supported = 0;
                    for (Placed record : placed) {
                        double overlap = footprintOverlap(candidate, record.box);
                        if (overlap > 0 && Math.abs(top(record.box) - z) <= EPSILON) {
                            lowers.add(record);
                            supported += overlap;
                        }
                    }
                    if (supported / (l * w) < MIN_SUPPORT_RATIO - 1e-6) {
                        continue;
                    }
                    boolean stackable = lowers.stream().allMatch(lower -> !lower.item.fragile()
                            && lower.topKg + item.weightKg() <= TOP_LOAD_FACTOR * lower.item.weightKg() + EPSILON);
                    if (!stackable) {
                        stackingRejected = true;
                        continue;
                    }
                    return new Attempt(new Placed(item, candidate, size.rotation(), lowers), stackingRejected);
                }
            }
        }
        return new Attempt(null, stackingRejected);
    }

    private static Set<Double> xCandidates(Vehicle vehicle, List<Box> layer, List<Placed> placed, double z, double l) {
        Set<Double> xs = new HashSet<>();
        xs.add(round(vehicle.length() - l));
        xs.add(0.0);
        for (Box box : layer) {
            xs.add(round(box.x() - l));
            xs.add(round(box.x() + box.length()));
        }
        for (Placed record : placed) {
            if (Math.abs(top(record.box) - z) <= EPSILON) {
                xs.add(round(record.box.x()));
                xs.add(round(record.box.x() + record.box.length() - l));
            }
        }
        xs.removeIf(x -> x < -EPSILON || x + l > vehicle.length() + EPSILON);
        return xs;
    }

    private static Set<Double> yCandidates(Vehicle vehicle, List<Box> layer, List<Placed> placed, double z, double w) {
        Set<Double> ys = new HashSet<>();
        ys.add(0.0);
        ys.add(round(vehicle.width() - w));
        for (Box box : layer) {
            ys.add(round(box.y() + box.width()));
            ys.add(round(box.y() - w));
        }
        for (Placed record : placed) {
            if (Math.abs(top(record.box) - z) <= EPSILON) {
                ys.add(round(record.box.y()));
                ys.add(round(record.box.y() + record.box.width() - w));
            }
        }
        ys.removeIf(y -> y < -EPSILON || y + w > vehicle.width() + EPSILON);
        return ys;
    }

    /** Kích thước đã xoay của mọi hướng cho phép qua được cửa sau; kiện dễ vỡ giữ mặt đứng (hai hướng). */
    private static List<Size> doorSizes(Vehicle vehicle, Item item) {
        double doorWidth = vehicle.doorWidth() != null ? vehicle.doorWidth() : vehicle.width();
        double doorHeight = vehicle.doorHeight() != null ? vehicle.doorHeight() : vehicle.height();
        double[] base = {item.length(), item.width(), item.height()};
        int count = item.fragile() ? 2 : 6;
        List<Size> sizes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int rotation = 0; rotation < count; rotation++) {
            double[] size = ManualLayoutValidator.orientedSize(base, rotation);
            if (size[1] > doorWidth + EPSILON || size[2] > doorHeight + EPSILON) {
                continue;
            }
            if (seen.add(size[0] + "x" + size[1] + "x" + size[2])) {
                sizes.add(new Size(size[0], size[1], size[2], rotation));
            }
        }
        return sizes;
    }

    private static void passLoad(List<Placed> supports, double weightKg, Set<Placed> loaded) {
        for (Placed lower : supports) {
            if (loaded.add(lower)) {
                lower.topKg += weightKg;
                passLoad(lower.supports, weightKg, loaded);
            }
        }
    }

    private static Snapshot snapshot(List<Box> boxes, AxleConfig axles) {
        double total = boxes.stream().mapToDouble(Box::weightKg).sum();
        return new Snapshot(total, ManualLayoutValidator.centerOfGravity(boxes),
                ManualLayoutValidator.axleLoads(boxes, axles));
    }

    private record Ranked(Box box, double rank, boolean pickup) {
    }

    /**
     * Số kiện bị che kín mặt sau (phía cửa) vì kiện nhận: kiện nhận bị một kiện giao muộn hơn che kín, hoặc kiện còn chở bị che kín
     * mà trước khi nhận thì chưa — chỉ tính chỗ chắn mới.
     */
    static int blockedCount(List<Onboard> onboard, List<Box> pickups, double pickupRank) {
        List<Ranked> before = new ArrayList<>();
        onboard.forEach(item -> before.add(new Ranked(item.box(), item.deliveryRank(), false)));
        List<Ranked> after = new ArrayList<>(before);
        pickups.forEach(box -> after.add(new Ranked(box, pickupRank, true)));
        int count = 0;
        for (Ranked target : after) {
            if (target.pickup()) {
                count += blocked(target, after) ? 1 : 0;
            } else if (target.rank() < pickupRank - 1e-9 && blocked(target, after) && !blocked(target, before)) {
                count++;
            }
        }
        return count;
    }

    private static boolean blocked(Ranked target, List<Ranked> all) {
        Box a = target.box();
        double rear = a.x() + a.length();
        List<double[]> covers = new ArrayList<>();
        for (Ranked other : all) {
            Box b = other.box();
            if (other == target || other.rank() <= target.rank() + 1e-9 || b.x() < rear - EPSILON) {
                continue;
            }
            double y0 = Math.max(a.y(), b.y());
            double y1 = Math.min(a.y() + a.width(), b.y() + b.width());
            double z0 = Math.max(a.z(), b.z());
            double z1 = Math.min(top(a), top(b));
            if (y1 - y0 > EPSILON && z1 - z0 > EPSILON) {
                covers.add(new double[]{y0, y1, z0, z1});
            }
        }
        return !covers.isEmpty() && coveredArea(covers) >= FULL_COVER_RATIO * a.width() * a.height();
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

    /** Thể tích lòng thùng trừ phần các hộp cố định chiếm (hộp cố định không chồng nhau), cm³. */
    private static double freeVolume(Vehicle vehicle, List<Box> fixed) {
        double total = vehicle.length() * vehicle.width() * vehicle.height();
        for (Box box : fixed) {
            total -= box.length() * box.width() * box.height();
        }
        return Math.max(0, total);
    }

    private static double footprintOverlap(Box a, Box b) {
        double dx = Math.min(a.x() + a.length(), b.x() + b.length()) - Math.max(a.x(), b.x());
        double dy = Math.min(a.y() + a.width(), b.y() + b.width()) - Math.max(a.y(), b.y());
        return dx > EPSILON && dy > EPSILON ? dx * dy : 0;
    }

    private static double top(Box box) {
        return box.z() + box.height();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
