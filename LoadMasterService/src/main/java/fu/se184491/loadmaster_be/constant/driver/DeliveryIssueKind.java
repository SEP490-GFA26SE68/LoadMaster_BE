package fu.se184491.loadmaster_be.constant.driver;

/**
 * Sự cố tài xế ghi cho một kiện tại điểm giao. Nền tảng chỉ lưu sự cố; kiện vẫn dỡ và giao cho bên nhận — bên gửi và bên nhận tự bàn.
 */
public enum DeliveryIssueKind { DAMAGED, MISSING, REFUSED, OTHER }
