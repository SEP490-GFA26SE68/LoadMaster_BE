package fu.se184491.loadmaster_be.constant.pickup;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Trạng thái yêu cầu nhận hàng dọc đường. {@code PENDING} chờ duyệt (còn luật không đạt) → {@code VALIDATED} (đạt cả mười luật) →
 * {@code APPROVED} (điều phối viên duyệt) → {@code LOADED} (tài xế đã nhận kiện lên xe) → {@code DELIVERED}. {@code REJECTED} là trạng
 * thái cuối. Duyệt được cả yêu cầu {@code PENDING} khi điều phối viên ghi lý do vượt luật.
 */
public enum PickupRequestStatus {
    PENDING, VALIDATED, REJECTED, APPROVED, LOADED, DELIVERED;

    private static final Map<PickupRequestStatus, Set<PickupRequestStatus>> TRANSITIONS = Map.of(
            PENDING, EnumSet.of(VALIDATED, REJECTED, APPROVED),
            VALIDATED, EnumSet.of(PENDING, APPROVED, REJECTED),
            REJECTED, EnumSet.noneOf(PickupRequestStatus.class),
            APPROVED, EnumSet.of(LOADED),
            LOADED, EnumSet.of(DELIVERED),
            DELIVERED, EnumSet.noneOf(PickupRequestStatus.class)
    );

    public boolean canMoveTo(PickupRequestStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }

    /** Còn chờ quyết định của điều phối viên: kiểm lại luật, duyệt, từ chối được. */
    public boolean open() {
        return this == PENDING || this == VALIDATED;
    }
}
