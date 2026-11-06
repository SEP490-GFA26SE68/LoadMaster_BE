package fu.se184491.loadmaster_be.exception;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerAppExceptionTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private ResponseEntity<ApiResponse<Void>> handle(AppException ex) {
        return handler.handleAppException(ex, new MockHttpServletRequest("GET", "/api/users/me"));
    }

    @Test
    @DisplayName("Lỗi nghiệp vụ trả mã trong phong bì: client phân biệt được tài khoản chưa có hồ sơ")
    void profileNotFoundCarriesCode() {
        ResponseEntity<ApiResponse<Void>> response = handle(new AppException(ErrorCode.USER_PROFILE_NOT_FOUND));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals("USER_PROFILE_NOT_FOUND", response.getBody().getCode());
        assertEquals(ErrorCode.USER_PROFILE_NOT_FOUND.getMessage(), response.getBody().getMessage());
    }

    @Test
    @DisplayName("Câu riêng vẫn giữ, mã vẫn là mã của ErrorCode")
    void customMessageKeepsCode() {
        ResponseEntity<ApiResponse<Void>> response = handle(new AppException(ErrorCode.OTP_INCORRECT, "Mã không đúng"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("OTP_INCORRECT", response.getBody().getCode());
        assertEquals("Mã không đúng", response.getBody().getMessage());
    }

    @Test
    @DisplayName("Hoàn tất xếp khi còn kiện chưa xử lý: 400 kèm mã INCOMPLETE_LOADING")
    void incompleteLoadingCarriesCode() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleIncompleteLoadingException(
                new IncompleteLoadingException("Còn 2 kiện"),
                new MockHttpServletRequest("POST", "/api/warehouse/tasks/1/complete"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals("INCOMPLETE_LOADING", response.getBody().getCode());
        assertEquals("Còn 2 kiện", response.getBody().getMessage());
    }
}
