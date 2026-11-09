package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;

import java.util.List;
import java.util.Objects;

/**
 * Hình học của phương án dùng cho luật kiện hỏng: kiện B tựa lên kiện A khi đáy B nằm đúng trên nóc A (lệch không quá {@link #TOLERANCE}) và
 * hai hình chiếu đứng chồng lên nhau một diện tích dương. Toạ độ và kích thước cùng đơn vị của phương án (cm, đã áp hướng xoay).
 */
public final class PlacementSupport {

    /** Dung sai chạm mặt, cùng đơn vị toạ độ. */
    static final double TOLERANCE = 0.5;

    private PlacementSupport() {
    }

    /** Số kiện của phương án tựa lên {@code target}. Vị trí thiếu toạ độ hay kích thước không tính. */
    public static int restingCount(PackagePlacement target, List<PackagePlacement> placements) {
        if (!complete(target)) {
            return 0;
        }
        double top = target.getPosZ() + target.getPackedHeight();
        int count = 0;
        for (PackagePlacement other : placements) {
            if (other == target || Objects.equals(other.getId(), target.getId()) || !complete(other)) {
                continue;
            }
            if (Math.abs(other.getPosZ() - top) <= TOLERANCE && footprintsOverlap(target, other)) {
                count++;
            }
        }
        return count;
    }

    private static boolean footprintsOverlap(PackagePlacement a, PackagePlacement b) {
        double x = Math.min(a.getPosX() + a.getPackedLength(), b.getPosX() + b.getPackedLength()) - Math.max(a.getPosX(), b.getPosX());
        double y = Math.min(a.getPosY() + a.getPackedWidth(), b.getPosY() + b.getPackedWidth()) - Math.max(a.getPosY(), b.getPosY());
        return x > TOLERANCE && y > TOLERANCE;
    }

    private static boolean complete(PackagePlacement p) {
        return p.getPosX() != null && p.getPosY() != null && p.getPosZ() != null
                && p.getPackedLength() != null && p.getPackedWidth() != null && p.getPackedHeight() != null;
    }
}
