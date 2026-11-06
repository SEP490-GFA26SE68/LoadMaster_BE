package fu.se184491.loadmaster_be.constant.warehouse;

/**
 * Hệ quả khi kho báo một kiện hỏng lúc xếp: {@code LEFT_OUT} — trong phương án không kiện nào tựa lên nó, kiện bị bỏ lại kho và kho xếp
 * tiếp; {@code REPLANNED} — có kiện tựa lên nó, chuyến về Đã lập kế hoạch để tối ưu lại.
 */
public enum DamageOutcome { LEFT_OUT, REPLANNED }
