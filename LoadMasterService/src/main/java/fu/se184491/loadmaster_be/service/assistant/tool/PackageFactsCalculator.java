package fu.se184491.loadmaster_be.service.assistant.tool;

import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Dữ kiện hình học của một kiện trong phương án, tính bằng code để trợ lý chỉ nhắc lại chứ không tự suy. Toạ độ là cm,
 * gốc ở vách trước, mép trái, sàn thùng; X tăng về phía cửa sau; kích thước đã áp hướng xoay (như dịch vụ tối ưu ghi).
 * Hàm thuần, không đọc cơ sở dữ liệu.
 */
public final class PackageFactsCalculator {

    /** Hai mặt cách nhau không quá chừng này theo Z là chạm nhau; đáy kiện thấp hơn chừng này là nằm trên sàn. */
    public static final double CONTACT_TOLERANCE_CM = 0.5;
    /** Hai đáy phải giao nhau quá chừng này theo cả X và Y; chỉ chạm cạnh thì không tính là tựa. */
    static final double MIN_OVERLAP_CM = 0.01;

    /**
     * Thứ tự dỡ: điểm giao sớm trước (kiện không có điểm cuối cùng), trong một điểm thì kiện xếp sau dỡ trước — cùng luật
     * với danh sách dỡ của tài xế ({@code DriverDeliveryServiceImpl}).
     */
    public static final Comparator<PlacementDetailResponse> UNLOAD_ORDER = Comparator
            .comparing(PlacementDetailResponse::getStopSequence, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(PlacementDetailResponse::getStepSequence, Comparator.nullsLast(Comparator.reverseOrder()));

    private PackageFactsCalculator() {
    }

    /** Mã các kiện ngay dưới {@code target}: mặt trên chạm đáy của nó và hai đáy giao nhau. Theo thứ tự của {@code all}. */
    public static List<String> restsOn(PlacementDetailResponse target, List<PlacementDetailResponse> all) {
        List<String> out = new ArrayList<>();
        for (PlacementDetailResponse other : all) {
            if (other != target && supports(other, target)) {
                addCode(out, other);
            }
        }
        return out;
    }

    /** Mã các kiện tựa lên {@code target} (cùng luật với {@link #restsOn}, chiều ngược). */
    public static List<String> restingOnIt(PlacementDetailResponse target, List<PlacementDetailResponse> all) {
        List<String> out = new ArrayList<>();
        for (PlacementDetailResponse other : all) {
            if (other != target && supports(target, other)) {
                addCode(out, other);
            }
        }
        return out;
    }

    /** Đáy kiện nằm ở sàn thùng (không có kiện nào bên dưới và {@code posZ} ≤ {@value #CONTACT_TOLERANCE_CM} cm). */
    public static boolean onFloor(PlacementDetailResponse target, List<PlacementDetailResponse> all) {
        return value(target.getPosZ()) <= CONTACT_TOLERANCE_CM && restsOn(target, all).isEmpty();
    }

    /**
     * Khoảng trống từ mặt kiện phía cửa tới cửa sau: {@code innerLength − (posX + packedLength)}, làm tròn 0,1 cm, không
     * âm. {@code null} khi chưa biết chiều dài lòng thùng.
     */
    public static Double distanceToDoorCm(PlacementDetailResponse target, Integer innerLengthCm) {
        if (innerLengthCm == null || target.getPosX() == null || target.getPackedLength() == null) {
            return null;
        }
        double gap = innerLengthCm - (value(target.getPosX()) + value(target.getPackedLength()));
        return round(Math.max(0, gap));
    }

    /**
     * Kiện nằm ngoài vùng của điểm giao của nó: vùng chứa tâm kiện ({@code stopZoneId}) khác {@code "ZONE-" + stopId}.
     * {@code null} khi phương án không dựng lại được vùng (kiện không có {@code stopZoneId}) hoặc kiện không có điểm giao.
     */
    public static Boolean outOfZone(PlacementDetailResponse target) {
        if (target.getStopZoneId() == null || target.getStopId() == null) {
            return null;
        }
        return !target.getStopZoneId().equals("ZONE-" + target.getStopId());
    }

    /** Các kiện theo thứ tự dỡ ({@link #UNLOAD_ORDER}); danh sách mới, không đổi {@code all}. */
    public static List<PlacementDetailResponse> unloadOrder(List<PlacementDetailResponse> all) {
        List<PlacementDetailResponse> out = new ArrayList<>(all);
        out.sort(UNLOAD_ORDER);
        return out;
    }

    /** {@code below} đỡ {@code above}: mặt trên của {@code below} chạm đáy {@code above} và hai đáy giao nhau. */
    private static boolean supports(PlacementDetailResponse below, PlacementDetailResponse above) {
        if (!complete(below) || !complete(above)) {
            return false;
        }
        double top = value(below.getPosZ()) + value(below.getPackedHeight());
        if (Math.abs(top - value(above.getPosZ())) > CONTACT_TOLERANCE_CM) {
            return false;
        }
        return overlap(below.getPosX(), below.getPackedLength(), above.getPosX(), above.getPackedLength()) > MIN_OVERLAP_CM
                && overlap(below.getPosY(), below.getPackedWidth(), above.getPosY(), above.getPackedWidth()) > MIN_OVERLAP_CM;
    }

    private static double overlap(BigDecimal startA, BigDecimal sizeA, BigDecimal startB, BigDecimal sizeB) {
        double a0 = value(startA);
        double b0 = value(startB);
        return Math.min(a0 + value(sizeA), b0 + value(sizeB)) - Math.max(a0, b0);
    }

    private static boolean complete(PlacementDetailResponse placement) {
        return placement.getPosX() != null && placement.getPosY() != null && placement.getPosZ() != null
                && placement.getPackedLength() != null && placement.getPackedWidth() != null
                && placement.getPackedHeight() != null;
    }

    private static void addCode(List<String> out, PlacementDetailResponse placement) {
        String code = Objects.requireNonNullElse(placement.getPackageCode(), placement.getTrackingBarcode());
        if (code != null) {
            out.add(code);
        }
    }

    private static double value(BigDecimal number) {
        return number == null ? 0 : number.doubleValue();
    }

    private static double round(double cm) {
        return Math.round(cm * 10) / 10.0;
    }
}
