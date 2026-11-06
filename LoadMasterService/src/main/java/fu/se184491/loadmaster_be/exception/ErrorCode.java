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

    // Đặt lại mật khẩu qua OTP (/api/auth/password-reset/*). Giữ 400 như trước để client cũ vẫn chạy; client đọc `code`.
    OTP_RESEND_COOLDOWN("Vui lòng chờ trước khi yêu cầu mã OTP mới", HttpStatus.BAD_REQUEST),
    OTP_REQUEST_LIMIT_EXCEEDED("Bạn đã yêu cầu OTP quá nhiều lần. Vui lòng thử lại sau", HttpStatus.BAD_REQUEST),
    OTP_EXPIRED("OTP đã hết hạn hoặc không hợp lệ", HttpStatus.BAD_REQUEST),
    OTP_INCORRECT("OTP không chính xác", HttpStatus.BAD_REQUEST),
    OTP_ATTEMPTS_EXCEEDED("Bạn đã nhập sai OTP quá nhiều lần", HttpStatus.BAD_REQUEST),
    RESET_TOKEN_INVALID("Reset token đã hết hạn hoặc không hợp lệ", HttpStatus.BAD_REQUEST),

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
    USER_IS_NOT_DRIVER("Người dùng hiện tại không phải là tài xế", HttpStatus.BAD_REQUEST),
    DRIVER_NOT_AVAILABLE(
            "Tài xế không ở trạng thái hoạt động",
            HttpStatus.CONFLICT
    ),
    USER_PROFILE_NOT_FOUND("Không tìm thấy thông tin hồ sơ người dùng", HttpStatus.NOT_FOUND),

    // -------------------------------------------------------------------------
    // Customer, Order & Cargo Package errors
    // -------------------------------------------------------------------------
    CUSTOMER_NOT_FOUND("Không tìm thấy khách hàng", HttpStatus.NOT_FOUND),
    ORDER_NOT_FOUND("Không tìm thấy đơn hàng vận chuyển", HttpStatus.NOT_FOUND),
    PACKAGE_NOT_FOUND("Không tìm thấy kiện hàng", HttpStatus.NOT_FOUND),
    QR_ENCRYPTION_FAILED("Lỗi mã hóa hoặc giải mã mã QR kiện hàng", HttpStatus.INTERNAL_SERVER_ERROR),
    PACKAGE_TYPE_NOT_FOUND("Không tìm thấy loại kiện hàng", HttpStatus.NOT_FOUND),
    TRACKING_BARCODE_ALREADY_EXISTS("Mã barcode kiện hàng đã tồn tại trong hệ thống", HttpStatus.CONFLICT),
    PACKAGE_TYPE_CODE_ALREADY_EXISTS("Mã loại kiện hàng đã tồn tại trong hệ thống", HttpStatus.CONFLICT),
    STACKING_RULE_NOT_FOUND("Không tìm thấy quy tắc chồng hàng", HttpStatus.NOT_FOUND),
    STACKING_RULE_ALREADY_EXISTS("Quy tắc chồng hàng giữa 2 loại kiện hàng này đã tồn tại", HttpStatus.CONFLICT),

    // -------------------------------------------------------------------------
    // Vehicle & Trip errors
    // -------------------------------------------------------------------------
    VEHICLE_NOT_AVAILABLE(
            "Xe không ở trạng thái sẵn sàng sử dụng",
            HttpStatus.CONFLICT
    ),

    DRIVER_COMPANY_MISMATCH(
            "Tài xế không thuộc công ty hiện tại",
            HttpStatus.BAD_REQUEST
    ),
    VEHICLE_CANNOT_CARRY_CARGO("Xe không chở được hàng của chuyến", HttpStatus.CONFLICT),
    VEHICLE_NOT_FOUND("Không tìm thấy phương tiện vận tải", HttpStatus.NOT_FOUND),
    VEHICLE_TYPE_NOT_FOUND("Không tìm thấy loại phương tiện", HttpStatus.NOT_FOUND),
    TRIP_NOT_FOUND("Không tìm thấy chuyến đi", HttpStatus.NOT_FOUND),
    DELIVERY_REQUIREMENT_NOT_FOUND("Không tìm thấy nhu cầu giao hàng", HttpStatus.NOT_FOUND),
    DELIVERY_REQUIREMENT_NOT_PENDING("Chỉ có thể xóa nhu cầu giao hàng đang chờ xử lý", HttpStatus.BAD_REQUEST),
    INVALID_DELIVERY_REQUIREMENT_TRANSITION("Chuyển trạng thái nhu cầu giao hàng không hợp lệ", HttpStatus.BAD_REQUEST),
    CARGO_SEGREGATION_CONFLICT("Kiện hàng xung đột quy tắc phân tách hàng hóa", HttpStatus.CONFLICT),
    // Vòng đời chuyến
    INVALID_TRIP_STATUS_TRANSITION("Chuyển trạng thái chuyến không hợp lệ", HttpStatus.CONFLICT),
    TRIP_NOT_EDITABLE("Chỉ sửa được chuyến ở trạng thái Nháp hoặc Đã lập kế hoạch", HttpStatus.CONFLICT),
    TRIP_NOT_CANCELLABLE("Chỉ huỷ được chuyến ở trạng thái Nháp, Đã lập kế hoạch hoặc Đang xếp hàng", HttpStatus.CONFLICT),
    TRIP_HAS_NO_STOPS("Chuyến chưa có điểm giao nào", HttpStatus.BAD_REQUEST),
    DELIVERY_REQUIREMENT_NOT_IN_TRIP("Yêu cầu giao không thuộc chuyến này", HttpStatus.BAD_REQUEST),
    TRIP_NOT_READY_FOR_LOADING("Chỉ bắt đầu xếp được chuyến ở trạng thái Đã lập kế hoạch", HttpStatus.CONFLICT),
    LOADING_NOT_COMPLETED("Kho chưa xếp xong hàng cho chuyến", HttpStatus.CONFLICT),
    TRIP_NOT_ASSIGNED_TO_DRIVER("Chuyến không được giao cho tài xế đang đăng nhập", HttpStatus.FORBIDDEN),
    LICENSE_PLATE_ALREADY_EXISTS("Biển số phương tiện đã tồn tại trong hệ thống", HttpStatus.CONFLICT),

    VEHICLE_WIDTH_HIGH("Phải nhập cả chiều rộng và chiều cao cửa xe", HttpStatus.BAD_REQUEST),
    VEHICLE_SIZE("Kích thước cửa xe phải lớn hơn 0", HttpStatus.BAD_REQUEST),
    WIDTH_RATIO("Chiều rộng cửa không được vượt quá chiều rộng thùng xe", HttpStatus.BAD_REQUEST),

    HIGH_RATIO("Chiều cao cửa không được vượt quá chiều cao thùng xe", HttpStatus.BAD_REQUEST),

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
    IMPORT_INVALID_LENGTH("Chiều dài phải là số dương (> 0 cm)", HttpStatus.BAD_REQUEST),
    IMPORT_INVALID_WIDTH("Chiều rộng phải là số dương (> 0 cm)", HttpStatus.BAD_REQUEST),
    IMPORT_INVALID_HEIGHT("Chiều cao phải là số dương (> 0 cm)", HttpStatus.BAD_REQUEST),
    IMPORT_INVALID_WEIGHT("Khối lượng phải là số dương (> 0 kg)", HttpStatus.BAD_REQUEST),
    IMPORT_INVALID_HANDLING_CLASS("Loại hàng không hợp lệ. Chỉ chấp nhận: STANDARD, FRAGILE", HttpStatus.BAD_REQUEST),
    IMPORT_BLANK_DESTINATION("Điểm đến / nơi nhận hàng không được để trống", HttpStatus.BAD_REQUEST),

    IMPORT_EXISTING_CODE("Mã kiện đã tồn tại trong hệ thống, hệ thống sẽ tạo bản ghi mới", HttpStatus.OK),

    // Import Excel - extended package fields
    IMPORT_BLANK_PACKAGE_CODE(
            "Mã kiện không được để trống",
            HttpStatus.BAD_REQUEST),

    IMPORT_INVALID_QUANTITY(
            "Số lượng phải là số nguyên dương",
            HttpStatus.BAD_REQUEST),

    IMPORT_INVALID_FRAGILITY_LEVEL(
            "Mức dễ vỡ không hợp lệ",
            HttpStatus.BAD_REQUEST),

    IMPORT_INVALID_MAX_TOP_LOAD(
            "Tải trọng đặt phía trên phải lớn hơn hoặc bằng 0 kg",
            HttpStatus.BAD_REQUEST),

    IMPORT_INVALID_MAX_LAYERS(
            "Số tầng tối đa phải là số nguyên lớn hơn hoặc bằng 1",
            HttpStatus.BAD_REQUEST),

    IMPORT_INVALID_MIN_SUPPORT_RATIO(
            "Tỷ lệ đỡ đáy phải nằm trong khoảng từ 0 đến 1",
            HttpStatus.BAD_REQUEST),

    IMPORT_INVALID_PRIORITY(
            "Độ ưu tiên không hợp lệ",
            HttpStatus.BAD_REQUEST),

    IMPORT_INVALID_ORIENTATION(
            "Hướng đặt kiện không hợp lệ",
            HttpStatus.BAD_REQUEST),

    IMPORT_INVALID_BOOLEAN(
            "Giá trị phải là Có/Không hoặc true/false",
            HttpStatus.BAD_REQUEST),

    IMPORT_PACKAGE_TYPE_NOT_FOUND(
            "Mã loại kiện không tồn tại trong công ty",
            HttpStatus.BAD_REQUEST),

    IMPORT_DUPLICATE_GENERATED_CODE(
            "Mã kiện được tạo theo số lượng bị trùng",
            HttpStatus.BAD_REQUEST),

    IMPORT_INVALID_PACKAGE_NAME(
            "Tên kiện vượt quá độ dài cho phép",
            HttpStatus.BAD_REQUEST),

    IMPORT_INVALID_PACKING_GROUP(
            "Nhóm kiện vượt quá độ dài cho phép",
            HttpStatus.BAD_REQUEST),

    IMPORT_DECIMAL_SCALE_EXCEEDED(
            "Giá trị số chỉ được phép tối đa 2 chữ số sau dấu phẩy",
            HttpStatus.BAD_REQUEST),

    IMPORT_INVALID_NUMBER_FORMAT(
            "Định dạng số không hợp lệ",
            HttpStatus.BAD_REQUEST),

    IMPORT_HAS_ERRORS("File import chứa lỗi dữ liệu, vui lòng kiểm tra preview trước khi xác nhận", HttpStatus.UNPROCESSABLE_ENTITY),
    EXPORT_TOO_MANY_PACKAGES("Tối đa 200 package mỗi lần xuất PDF", HttpStatus.BAD_REQUEST),

    // -------------------------------------------------------------------------
    // Optimization & Load Plan errors
    // -------------------------------------------------------------------------
    LOAD_PLAN_NOT_FOUND("Không tìm thấy kế hoạch xếp hàng", HttpStatus.NOT_FOUND),
    PLACEMENT_NOT_FOUND("Không tìm thấy vị trí kiện hàng", HttpStatus.NOT_FOUND),
    PLACEMENT_NOT_IN_PLAN("Vị trí kiện hàng không thuộc về kế hoạch xếp hàng này", HttpStatus.BAD_REQUEST),
    PLAN_ALREADY_APPROVED("Kế hoạch xếp hàng này đã được phê duyệt", HttpStatus.BAD_REQUEST),
    PLAN_TRIP_NOT_PLANNED("Chỉ duyệt được phương án khi chuyến ở trạng thái Đã lập kế hoạch", HttpStatus.CONFLICT),
    TRIP_LOCKED("Chuyến không ở trạng thái Đã lập kế hoạch nên không sửa được phương án", HttpStatus.CONFLICT),
    PLAN_HAS_NO_PLACEMENTS("Kế hoạch xếp hàng không có kiện hàng nào", HttpStatus.BAD_REQUEST),
    PLAN_LIFO_INVALID("Thứ tự dỡ hàng LIFO không hợp lệ", HttpStatus.BAD_REQUEST),
    OPTIMIZATION_JOB_NOT_FOUND("Không tìm thấy yêu cầu tối ưu", HttpStatus.NOT_FOUND),
    OPTIMIZATION_SERVICE_UNAVAILABLE("Dịch vụ tối ưu xếp hàng không khả dụng", HttpStatus.SERVICE_UNAVAILABLE),
    INSUFFICIENT_CREDITS("Công ty không đủ credit để chạy tối ưu xếp hàng", HttpStatus.PAYMENT_REQUIRED),
    PINNED_SET_INVALID("Bộ kiện đã ghim không đứng vững; chưa chạy tối ưu và không trừ credit", HttpStatus.BAD_REQUEST),
    PLAN_STALE("Phương án lỗi thời: hàng, xe hoặc điểm giao của chuyến đã đổi sau khi tối ưu", HttpStatus.CONFLICT),
    MANUAL_EDIT_INVALID("Bản chỉnh tay không hợp lệ", HttpStatus.BAD_REQUEST),
    MANUAL_EDIT_OUT_OF_BOUNDS("Có kiện nằm ngoài lòng thùng", HttpStatus.UNPROCESSABLE_ENTITY),
    MANUAL_EDIT_OVERLAP("Có kiện chồng lấn kiện khác hoặc vật cản", HttpStatus.UNPROCESSABLE_ENTITY),
    MANUAL_EDIT_UNSUPPORTED("Có kiện không đủ diện tích đỡ bên dưới", HttpStatus.UNPROCESSABLE_ENTITY),
    MANUAL_EDIT_AXLE_OVERLOAD("Bản chỉnh làm tải trục vượt giới hạn", HttpStatus.UNPROCESSABLE_ENTITY),
    MANUAL_EDIT_ROTATION_NOT_ALLOWED("Loại kiện không cho xoay kiện theo hướng này", HttpStatus.UNPROCESSABLE_ENTITY),

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
    TRIP_NOT_LOADING("Chuyến không ở trạng thái Đang xếp hàng", HttpStatus.CONFLICT),
    STAGING_INCOMPLETE("Còn kiện chưa soạn hoặc báo thiếu chờ điều phối viên quyết, chưa xếp được", HttpStatus.CONFLICT),
    PACKAGE_NOT_IN_LOADING_PLAN("Kiện không thuộc phương án đang xếp của chuyến", HttpStatus.NOT_FOUND),
    PACKAGE_ALREADY_STAGED("Kiện đã được soạn vào khu chờ", HttpStatus.CONFLICT),
    STAGING_SHORTAGE_NOT_FOUND("Không tìm thấy báo thiếu", HttpStatus.NOT_FOUND),
    STAGING_SHORTAGE_NOT_OPEN("Báo thiếu này đã được xử lý", HttpStatus.CONFLICT),
    LOADING_MANUAL_CONFIRM_PENDING("Còn xác nhận tay của kho chờ điều phối viên duyệt, chưa hoàn tất xếp được", HttpStatus.CONFLICT),
    SEAL_INVALID("Số seal cần từ 1 đến 32 ký tự", HttpStatus.BAD_REQUEST),
    SEAL_NOT_ALLOWED("Chỉ ghi được số seal khi đã xếp xong và xe chưa xuất phát", HttpStatus.CONFLICT),
    PACKAGE_FLAGGED("Kiện đang mang cờ không tìm thấy / hư hỏng: gỡ cờ trước khi đưa vào chuyến", HttpStatus.CONFLICT),
    PACKAGE_NOT_FLAGGED("Kiện không mang cờ nào", HttpStatus.CONFLICT),

    // -------------------------------------------------------------------------
    // Driver & Unloading errors
    // -------------------------------------------------------------------------
    DELIVERY_STOP_NOT_FOUND("Không tìm thấy điểm dừng giao hàng", HttpStatus.NOT_FOUND),
    NO_PENDING_DELIVERY_STOP("Không còn điểm dừng nào cần giao hàng", HttpStatus.NOT_FOUND),
    DUPLICATE_UNLOAD("Kiện hàng này đã được dỡ trước đó", HttpStatus.CONFLICT),
    WRONG_DELIVERY_STOP("Kiện hàng không thuộc điểm dừng hiện tại", HttpStatus.BAD_REQUEST),
    INCOMPLETE_STOP_UNLOAD("Điểm dừng chưa hoàn tất dỡ hàng", HttpStatus.BAD_REQUEST),
    DELIVERY_STOP_ALREADY_COMPLETED("Điểm giao đã hoàn tất", HttpStatus.CONFLICT),
    DELIVERY_STOP_ALREADY_ARRIVED("Đã báo đến điểm giao này trước đó", HttpStatus.CONFLICT),
    DELIVERY_STOP_NOT_CURRENT("Chưa tới lượt điểm giao này: còn điểm giao trước chưa hoàn tất", HttpStatus.CONFLICT),

    // -------------------------------------------------------------------------
    // Subscription & Payment errors (Sprint 8)
    // -------------------------------------------------------------------------
    ACTIVE_SUBSCRIPTION_ALREADY_EXISTS("Doanh nghiệp đã có gói đăng ký đang hoạt động", HttpStatus.CONFLICT),
    NO_ACTIVE_SUBSCRIPTION("Doanh nghiệp chưa có gói đăng ký nào đang hoạt động", HttpStatus.NOT_FOUND),
    SUBSCRIPTION_PLAN_NOT_FOUND("Không tìm thấy gói đăng ký", HttpStatus.NOT_FOUND),
    PAYMENT_TRANSACTION_NOT_FOUND("Không tìm thấy giao dịch thanh toán", HttpStatus.NOT_FOUND),
    INVALID_PAYMENT_SIGNATURE("Chữ ký thanh toán không hợp lệ", HttpStatus.BAD_REQUEST),

    // -------------------------------------------------------------------------
    // Monitoring & GPS tracking errors (Sprint 6)
    // -------------------------------------------------------------------------
    GPS_TRIP_NOT_IN_TRANSIT("Chuyến đi không ở trạng thái đang vận chuyển", HttpStatus.BAD_REQUEST),
    TRIP_EXCEPTION_NOT_FOUND("Không tìm thấy sự cố chuyến đi", HttpStatus.NOT_FOUND),
    EXCEPTION_STATUS_INVALID("Sự cố chuyến đi không ở trạng thái cho phép thao tác này", HttpStatus.CONFLICT),
    CONTACT_NOTE_REQUIRED("Phải ghi lại nội dung đã liên hệ khách trước khi gia hạn", HttpStatus.BAD_REQUEST),
    REQUIREMENT_DEADLINE_PAST("Hạn giao mới phải ở tương lai", HttpStatus.BAD_REQUEST),
    INVALID_REROUTE_INDEX("Chỉ số tuyến thay thế không hợp lệ", HttpStatus.BAD_REQUEST),
    NO_GPS_LOCATION("Chuyến đi chưa có vị trí GPS", HttpStatus.BAD_REQUEST),

    // -------------------------------------------------------------------------
    // Route Planning errors (Sprint 7 / Phương án 2)
    // -------------------------------------------------------------------------
    ROUTE_PLAN_NOT_FOUND("Không tìm thấy kế hoạch lộ trình", HttpStatus.NOT_FOUND),
    ROUTE_LEG_NOT_FOUND("Không tìm thấy chặng lộ trình", HttpStatus.NOT_FOUND),
    ROUTE_SUGGESTION_NOT_FOUND("Không tìm thấy đề xuất đổi tuyến", HttpStatus.NOT_FOUND),

    // -------------------------------------------------------------------------
    // Partner Contract & Support Guest errors (Sprint 4)
    // -------------------------------------------------------------------------
    CONTACT_REQUEST_NOT_FOUND("Không tìm thấy yêu cầu liên hệ", HttpStatus.NOT_FOUND),
    CONTRACT_NOT_FOUND("Không tìm thấy hợp đồng", HttpStatus.NOT_FOUND),
    INVOICE_NOT_FOUND("Không tìm thấy hóa đơn", HttpStatus.NOT_FOUND),
    TRIAL_REQUEST_NOT_FOUND("Không tìm thấy đơn yêu cầu tạo tài khoản dùng thử", HttpStatus.NOT_FOUND),
    TRIAL_REQUEST_ALREADY_PROCESSED("Đơn yêu cầu tạo tài khoản dùng thử đã được xử lý trước đó", HttpStatus.BAD_REQUEST),
    CANNOT_DIRECTLY_APPROVE_DEMO_REQUEST("Yêu cầu dùng thử phải được chuyển sang Quản lý nền tảng duyệt tạo tài khoản", HttpStatus.BAD_REQUEST),

    // -------------------------------------------------------------------------
    // Company Service Agreement errors
    // -------------------------------------------------------------------------
    AGREEMENT_NOT_FOUND("Không tìm thấy thỏa thuận dịch vụ của doanh nghiệp", HttpStatus.NOT_FOUND),
    AGREEMENT_ALREADY_SIGNED("Thỏa thuận dịch vụ này đã được ký trước đó", HttpStatus.BAD_REQUEST),
    AGREEMENT_DEADLINE_EXPIRED("Đã quá thời hạn 3 ngày ký thỏa thuận dịch vụ. Tài khoản đã bị khóa", HttpStatus.FORBIDDEN),

    // -------------------------------------------------------------------------
    // Driver delivery: sự cố theo kiện, xác nhận tay khi dỡ, phương án đã xếp
    // -------------------------------------------------------------------------
    DELIVERY_STOP_NOT_ARRIVED("Chưa báo đến điểm giao này", HttpStatus.CONFLICT),
    DELIVERY_ISSUE_NOTE_REQUIRED("Sự cố loại Khác phải có ghi chú", HttpStatus.BAD_REQUEST),
    MANUAL_CONFIRM_NOTE_REQUIRED("Xác nhận tay với lý do Khác phải có ghi chú", HttpStatus.BAD_REQUEST),
    MANUAL_CONFIRM_PENDING("Còn xác nhận tay chờ điều phối viên duyệt", HttpStatus.CONFLICT),
    MANUAL_CONFIRM_NOT_FOUND("Không tìm thấy xác nhận tay", HttpStatus.NOT_FOUND),
    MANUAL_CONFIRM_NOT_PENDING("Xác nhận tay này đã được xử lý", HttpStatus.CONFLICT),
    NO_LOADED_PLAN("Chuyến chưa có phương án kho đã xếp", HttpStatus.NOT_FOUND),

    // -------------------------------------------------------------------------
    // Nhận hàng dọc đường
    // -------------------------------------------------------------------------
    PICKUP_REQUEST_NOT_FOUND("Không tìm thấy yêu cầu nhận hàng dọc đường", HttpStatus.NOT_FOUND),
    PICKUP_INVALID("Yêu cầu nhận hàng dọc đường thiếu hoặc sai dữ liệu", HttpStatus.BAD_REQUEST),
    PICKUP_PACKAGES_REQUIRED("Yêu cầu nhận hàng phải có ít nhất một kiện", HttpStatus.BAD_REQUEST),
    PICKUP_TRIP_NOT_IN_TRANSIT("Chỉ nhận hàng dọc đường khi chuyến đang vận chuyển", HttpStatus.CONFLICT),
    INVALID_PICKUP_STATUS_TRANSITION("Yêu cầu nhận hàng không ở trạng thái cho phép thao tác này", HttpStatus.CONFLICT),
    PICKUP_ROUTE_UNAVAILABLE("Chưa kiểm được luật nhận hàng: thiếu toạ độ điểm, vị trí xe hoặc kích thước thùng", HttpStatus.CONFLICT),
    PICKUP_REASON_REQUIRED("Phải ghi lý do", HttpStatus.BAD_REQUEST),
    PICKUP_PACKAGE_NOT_AT_STOP("Kiện nhận không thuộc điểm nhận hiện tại", HttpStatus.CONFLICT),
    PICKUP_PACKAGE_ALREADY_PICKED("Kiện nhận này đã lên xe", HttpStatus.CONFLICT),
    PICKUP_STOP_INCOMPLETE("Còn kiện nhận chưa đối chiếu ở điểm nhận", HttpStatus.CONFLICT);

    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String message, HttpStatus httpStatus) {
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
