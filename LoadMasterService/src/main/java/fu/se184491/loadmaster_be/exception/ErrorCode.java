package fu.se184491.loadmaster_be.exception;



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
    COMPANY_ALREADY_EXISTS("Mã công ty đã tồn tại trong hệ thống", HttpStatus.CONFLICT),
    COMPANY_REQUIRED("Yêu cầu phải có thông tin công ty đối với các vai trò ở phạm vi công ty.", HttpStatus.BAD_REQUEST),
    USER_COMPANY_NOT_FOUND("Người dùng không thuộc công ty nào", HttpStatus.NOT_FOUND),
    TAX_CODE_ALREADY_EXISTS("Mã số thuế đã tồn tại trong hệ thống", HttpStatus.CONFLICT),
    ROLE_NOT_FOUND("Không tìm thấy vai trò người dùng", HttpStatus.NOT_FOUND),
    EMAIL_ALREADY_EXISTS("Email đã được sử dụng", HttpStatus.CONFLICT),

    USER_PROFILE_NOT_FOUND("Không tìm thấy thông tin hồ sơ người dùng", HttpStatus.NOT_FOUND),

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
    DELIVERY_REQUIREMENT_NOT_FOUND("Không tìm thấy nhu cầu giao hàng", HttpStatus.NOT_FOUND),
    DELIVERY_REQUIREMENT_NOT_PENDING("Chỉ có thể xóa nhu cầu giao hàng đang chờ xử lý", HttpStatus.BAD_REQUEST),
    INVALID_DELIVERY_REQUIREMENT_TRANSITION("Chuyển trạng thái nhu cầu giao hàng không hợp lệ", HttpStatus.BAD_REQUEST),
    CARGO_SEGREGATION_CONFLICT("Kiện hàng xung đột quy tắc phân tách hàng hóa", HttpStatus.CONFLICT),


    // -------------------------------------------------------------------------
    // Subscription & Payment errors
    // -------------------------------------------------------------------------
    SUBSCRIPTION_NOT_FOUND("Không tìm thấy thông tin gói dịch vụ", HttpStatus.NOT_FOUND),
    SUBSCRIPTION_PLAN_ALREADY_EXISTS("Mã gói dịch vụ đã tồn tại", HttpStatus.CONFLICT),
    SUBSCRIPTION_TIER_ALREADY_EXISTS("Đã có một gói dịch vụ khác đang kích hoạt cho Tier này", HttpStatus.CONFLICT),
    PLAN_IN_USE("Không thể xóa gói dịch vụ đang có doanh nghiệp sử dụng", HttpStatus.CONFLICT),
    PAYMENT_FAILED("Giao dịch thanh toán thất bại", HttpStatus.PAYMENT_REQUIRED),

    // -------------------------------------------------------------------------
    // File & Export errors
    // -------------------------------------------------------------------------
    EXPORT_FAILED("Xuất dữ liệu thất bại", HttpStatus.INTERNAL_SERVER_ERROR),
    FILE_INVALID("File không hợp lệ hoặc vượt quá kích thước cho phép", HttpStatus.BAD_REQUEST),
    UNSUPPORTED_FILE_TYPE("Loại file không được hỗ trợ. Chỉ chấp nhận .csv và .xlsx", HttpStatus.BAD_REQUEST),
    EMPTY_FILE("File rỗng hoặc không có dữ liệu để xử lý", HttpStatus.BAD_REQUEST),
    FILE_SIZE_LIMIT_EXCEEDED("Kích thước file vượt quá giới hạn tối đa cho phép (10MB)", HttpStatus.PAYLOAD_TOO_LARGE),
    BATCH_SIZE_LIMIT_EXCEEDED("Số lượng bản ghi trong file vượt quá giới hạn tối đa cho phép (1000 dòng)", HttpStatus.BAD_REQUEST),

    // -------------------------------------------------------------------------
    // Import batch validation - row level
    // -------------------------------------------------------------------------
    IMPORT_DUPLICATE_CODE("Mã kiện bị trùng lặp trong cùng file", HttpStatus.BAD_REQUEST),
    IMPORT_INVALID_LENGTH("Chiều dài phải là số nguyên dương (> 0 mm)", HttpStatus.BAD_REQUEST),
    IMPORT_INVALID_WIDTH("Chiều rộng phải là số nguyên dương (> 0 mm)", HttpStatus.BAD_REQUEST),
    IMPORT_INVALID_HEIGHT("Chiều cao phải là số nguyên dương (> 0 mm)", HttpStatus.BAD_REQUEST),
    IMPORT_INVALID_WEIGHT("Khối lượng phải là số dương (> 0 kg)", HttpStatus.BAD_REQUEST),
    IMPORT_INVALID_HANDLING_CLASS("Loại hàng không hợp lệ. Chỉ chấp nhận: STANDARD, FRAGILE, REFRIGERATED, HAZARDOUS, HIGH_VALUE", HttpStatus.BAD_REQUEST),
    IMPORT_BLANK_DESTINATION("Điểm đến / nơi nhận hàng không được để trống", HttpStatus.BAD_REQUEST),
    IMPORT_EXISTING_CODE("Mã kiện đã tồn tại trong hệ thống, hệ thống sẽ tạo bản ghi mới", HttpStatus.OK),
    IMPORT_HAS_ERRORS("File import chứa lỗi dữ liệu, vui lòng kiểm tra preview trước khi xác nhận", HttpStatus.UNPROCESSABLE_ENTITY),
    EXPORT_TOO_MANY_PACKAGES("Tối đa 200 package mỗi lần xuất PDF", HttpStatus.BAD_REQUEST),

    // -------------------------------------------------------------------------
    // Optimization & Load Plan errors
    // -------------------------------------------------------------------------
    LOAD_PLAN_NOT_FOUND("Không tìm thấy kế hoạch xếp hàng", HttpStatus.NOT_FOUND),
    PLACEMENT_NOT_FOUND("Không tìm thấy vị trí kiện hàng", HttpStatus.NOT_FOUND),
    PLACEMENT_NOT_IN_PLAN("Vị trí kiện hàng không thuộc về kế hoạch xếp hàng này", HttpStatus.BAD_REQUEST),

    // -------------------------------------------------------------------------
    // Warehouse & Loading errors
    // -------------------------------------------------------------------------
    NO_APPROVED_LOAD_PLAN("Chuyến đi chưa có kế hoạch xếp hàng được duyệt", HttpStatus.BAD_REQUEST),
    TRIP_ALREADY_LOADING("Chuyến đi đang trong quá trình bốc xếp hàng", HttpStatus.BAD_REQUEST),
    LOADING_EXECUTION_NOT_FOUND("Không tìm thấy thông tin phiên bốc xếp", HttpStatus.NOT_FOUND),
    PLACEMENT_ALREADY_CONFIRMED("Vị trí kiện hàng đã được xác nhận", HttpStatus.BAD_REQUEST),
    DEVIATION_ALREADY_RECORDED("Sai lệch cho vị trí kiện hàng này đã được ghi nhận", HttpStatus.BAD_REQUEST),
    INVALID_SEQUENCE_ORDER("Thứ tự bốc xếp không hợp lệ, vui lòng xử lý các kiện hàng trước", HttpStatus.BAD_REQUEST),
    INCOMPLETE_LOADING("Quá trình bốc xếp chưa hoàn tất", HttpStatus.BAD_REQUEST),

    // -------------------------------------------------------------------------
    // Driver & Unloading errors
    // -------------------------------------------------------------------------
    DELIVERY_STOP_NOT_FOUND("Không tìm thấy điểm dừng giao hàng", HttpStatus.NOT_FOUND),
    NO_PENDING_DELIVERY_STOP("Không còn điểm dừng nào cần giao hàng", HttpStatus.NOT_FOUND),
    DUPLICATE_UNLOAD("Kiện hàng này đã được dỡ trước đó", HttpStatus.CONFLICT),
    WRONG_DELIVERY_STOP("Kiện hàng không thuộc điểm dừng hiện tại", HttpStatus.BAD_REQUEST),
    INCOMPLETE_STOP_UNLOAD("Điểm dừng chưa hoàn tất dỡ hàng", HttpStatus.BAD_REQUEST),

    // -------------------------------------------------------------------------
    // Subscription & Payment errors (Sprint 8)
    // -------------------------------------------------------------------------
    ACTIVE_SUBSCRIPTION_ALREADY_EXISTS("Doanh nghiệp đã có gói đăng ký đang hoạt động", HttpStatus.CONFLICT),
    NO_ACTIVE_SUBSCRIPTION("Doanh nghiệp chưa có gói đăng ký nào đang hoạt động", HttpStatus.NOT_FOUND),
    SUBSCRIPTION_PLAN_NOT_FOUND("Không tìm thấy gói đăng ký", HttpStatus.NOT_FOUND),
    PAYMENT_TRANSACTION_NOT_FOUND("Không tìm thấy giao dịch thanh toán", HttpStatus.NOT_FOUND),
    INVALID_PAYMENT_SIGNATURE("Chữ ký thanh toán không hợp lệ", HttpStatus.BAD_REQUEST);

    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String message, HttpStatus httpStatus) {
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
