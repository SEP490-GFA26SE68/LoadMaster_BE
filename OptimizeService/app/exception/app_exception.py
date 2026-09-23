from app.exception.error_code import ErrorCode


class AppException(Exception):
    def __init__(self, error_code: ErrorCode, custom_message: str | None = None):
        self.error_code = error_code
        self.message = custom_message or error_code.message
        self.http_status = error_code.http_status
        super().__init__(self.message)


class ServiceAuthenticationException(Exception):
    """
    Ném ra khi không thể lấy service token từ Keycloak
    (vd: Keycloak down, sai client secret, lỗi mạng).
    Maps về HTTP 503 Service Unavailable.
    """
    def __init__(self, message: str = "Không thể xác thực với Keycloak service"):
        self.message = message
        self.http_status = 503
        super().__init__(self.message)
