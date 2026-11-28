package fu.se184491.loadmaster_be.constant.planning;

/**
 * Ưu tiên yêu cầu giao (D-93): số packing càng lớn càng ưu tiên; chỉ {@link #URGENT} bắt buộc xếp.
 */
public enum DeliveryRequirementPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT;

    /** Số packing 1–4; mặc định khi không có yêu cầu là {@link #NORMAL} (= 2). */
    public int packingRank() {
        return switch (this) {
            case LOW -> 1;
            case NORMAL -> 2;
            case HIGH -> 3;
            case URGENT -> 4;
        };
    }

    public boolean mustLoad() {
        return this == URGENT;
    }
}
