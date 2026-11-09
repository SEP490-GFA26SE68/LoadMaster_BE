package fu.se184491.loadmaster_be.constant.cargo;

/**
 * Trạng thái kiện. {@code RETURNED}: kiện có sự cố lúc giao (khách từ chối, hỏng…) mà chưa dỡ khi tài xế hoàn tất điểm —
 * kiện ở lại xe, được chở về kho.
 */
public enum PackageStatus { PENDING, PLANNED, LOADED, DELIVERED, RETURNED, CANCELLED }
