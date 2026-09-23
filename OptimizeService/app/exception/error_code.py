from enum import Enum


class ErrorCode(str, Enum):
    # System & General errors
    INTERNAL_SERVER_ERROR = ("INTERNAL_SERVER_ERROR", "Đã xảy ra lỗi hệ thống", 500)
    INVALID_INPUT = ("INVALID_INPUT", "Dữ liệu đầu vào không hợp lệ", 400)
    VALIDATION_ERROR = ("VALIDATION_ERROR", "Lỗi xác thực dữ liệu", 400)
    RESOURCE_NOT_FOUND = ("RESOURCE_NOT_FOUND", "Không tìm thấy tài nguyên yêu cầu", 404)

    # Auth errors
    UNAUTHENTICATED = ("UNAUTHENTICATED", "Yêu cầu xác thực tài khoản", 401)
    UNAUTHORIZED = ("UNAUTHORIZED", "Bạn không có quyền thực hiện hành động này", 403)

    # Trip & Vehicle errors
    TRIP_NOT_FOUND = ("TRIP_NOT_FOUND", "Không tìm thấy chuyến đi", 404)
    VEHICLE_NOT_FOUND = ("VEHICLE_NOT_FOUND", "Không tìm thấy phương tiện vận tải", 404)
    VEHICLE_TYPE_NOT_FOUND = ("VEHICLE_TYPE_NOT_FOUND", "Không tìm thấy loại phương tiện", 404)
    PACKAGE_NOT_FOUND = ("PACKAGE_NOT_FOUND", "Không tìm thấy kiện hàng", 404)

    # Optimization & Engine errors
    OPTIMIZATION_JOB_NOT_FOUND = ("OPTIMIZATION_JOB_NOT_FOUND", "Không tìm thấy yêu cầu tối ưu", 404)
    OPTIMIZATION_FAILED = ("OPTIMIZATION_FAILED", "Chạy thuật toán tối ưu thất bại", 500)
    OPTIMIZATION_TIMEOUT = ("OPTIMIZATION_TIMEOUT", "Hết thời gian chờ thuật toán tối ưu", 504)
    ENGINE_TIMEOUT = ("ENGINE_TIMEOUT", "Hết thời gian chờ phản hồi từ Engine tối ưu", 504)
    SERVICE_UNAVAILABLE = ("SERVICE_UNAVAILABLE", "Dịch vụ Engine tối ưu không khả dụng", 503)
    AUTH_ERROR = ("AUTH_ERROR", "Lỗi xác thực với Engine tối ưu", 401)
    AUTH_FORBIDDEN = ("AUTH_FORBIDDEN", "Không có quyền gọi Engine tối ưu", 403)
    LOAD_PLAN_NOT_FOUND = ("LOAD_PLAN_NOT_FOUND", "Không tìm thấy kế hoạch xếp hàng", 404)
    PLAN_ALREADY_APPROVED = ("PLAN_ALREADY_APPROVED", "Kế hoạch xếp hàng này đã được phê duyệt", 400)
    PLAN_HAS_NO_PLACEMENTS = ("PLAN_HAS_NO_PLACEMENTS", "Kế hoạch xếp hàng không có kiện hàng nào", 400)
    PLAN_LIFO_INVALID = ("PLAN_LIFO_INVALID", "Thứ tự dỡ hàng LIFO không hợp lệ", 400)

    def __new__(cls, code: str, message: str, http_status: int):
        obj = str.__new__(cls, code)
        obj._value_ = code
        obj.code = code
        obj.message = message
        obj.http_status = http_status
        return obj
