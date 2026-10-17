package fu.se184491.loadmaster_be.constant.monitoring;

/**
 * Vì sao sự cố lên quản lý: điều phối viên không tìm được tuyến, hoặc quá
 * {@link #AFTER_MINUTES} phút chưa xử lý.
 */
public enum ExceptionEscalationReason {
    NO_ROUTE,
    TIMEOUT;

    public static final int AFTER_MINUTES = 30;
}
