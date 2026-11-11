package fu.se184491.loadmaster_be.constant.support;

public enum TrialRequestStatus {
    PENDING,                 // Chờ System Manager thẩm định (đã qua AI pre-check)
    PENDING_ADMIN_CREATION,  // System Manager đã chấp thuận, chờ System Admin tạo tài khoản
    APPROVED,                // System Admin đã tạo tài khoản thành công
    REJECTED                 // Đơn bị từ chối (bởi AI tự xử lý hoặc System Manager)
}
