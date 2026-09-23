package fu.se184491.loadmaster_be.exception;

import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.billing.Subscription;
import fu.se184491.loadmaster_be.entity.account.Role;
import fu.se184491.loadmaster_be.entity.account.User;


import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // -------------------------------------------------------------------------
    // System & General errors
    // -------------------------------------------------------------------------
    UNCATEGORIZED_EXCEPTION("Lỗi hệ thống chưa được phân loại", HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_SERVER_ERROR("Đã xảy ra lỗi hệ thống", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_INPUT("Dữ liệu đầu vào không hợp lệ", HttpStatus.BAD_REQUEST),
    VALIDATION_ERROR("Lỗi xác thực dữ liệu", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND("Không tìm thấy tài nguyên yêu cầu", HttpStatus.NOT_FOUND),

    // -------------------------------------------------------------------------
    // Authentication & Authorization errors
    // -------------------------------------------------------------------------
    UNAUTHENTICATED("Yêu cầu xác thực tài khoản", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED("Bạn không có quyền thực hiện hành động này", HttpStatus.FORBIDDEN),
    INVALID_CREDENTIALS("Email hoặc mật khẩu không chính xác", HttpStatus.BAD_REQUEST),

    // -------------------------------------------------------------------------
    // User, Company & Role errors
    // -------------------------------------------------------------------------
    USER_NOT_FOUND("Không tìm thấy người dùng", HttpStatus.NOT_FOUND),
    USER_ALREADY_EXISTS("Người dùng với email này đã tồn tại", HttpStatus.CONFLICT),
    COMPANY_NOT_FOUND("Không tìm thấy thông tin công ty", HttpStatus.NOT_FOUND),
    ROLE_NOT_FOUND("Không tìm thấy vai trò người dùng", HttpStatus.NOT_FOUND),

    // -------------------------------------------------------------------------
    // Customer, Order & Cargo Package errors
    // -------------------------------------------------------------------------
    CUSTOMER_NOT_FOUND("Không tìm thấy khách hàng", HttpStatus.NOT_FOUND),
    ORDER_NOT_FOUND("Không tìm thấy đơn hàng vận chuyển", HttpStatus.NOT_FOUND),
    PACKAGE_NOT_FOUND("Không tìm thấy kiện hàng", HttpStatus.NOT_FOUND),
    PACKAGE_TYPE_NOT_FOUND("Không tìm thấy loại kiện hàng", HttpStatus.NOT_FOUND),

    // -------------------------------------------------------------------------
    // Vehicle & Trip errors
    // -------------------------------------------------------------------------
    VEHICLE_NOT_FOUND("Không tìm thấy phương tiện vận tải", HttpStatus.NOT_FOUND),
    VEHICLE_TYPE_NOT_FOUND("Không tìm thấy loại phương tiện", HttpStatus.NOT_FOUND),
    TRIP_NOT_FOUND("Không tìm thấy chuyến đi", HttpStatus.NOT_FOUND),


    // -------------------------------------------------------------------------
    // Subscription & Payment errors
    // -------------------------------------------------------------------------
    SUBSCRIPTION_NOT_FOUND("Không tìm thấy thông tin gói dịch vụ", HttpStatus.NOT_FOUND),
    PAYMENT_FAILED("Giao dịch thanh toán thất bại", HttpStatus.PAYMENT_REQUIRED),

    // -------------------------------------------------------------------------
    // File & Export errors
    // -------------------------------------------------------------------------
    EXPORT_FAILED("Xuất dữ liệu thất bại", HttpStatus.INTERNAL_SERVER_ERROR),
    FILE_INVALID("File không hợp lệ hoặc vượt quá kích thước cho phép", HttpStatus.BAD_REQUEST);

    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String message, HttpStatus httpStatus) {
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
