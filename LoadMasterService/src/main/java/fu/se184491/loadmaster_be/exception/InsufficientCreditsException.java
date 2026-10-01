package fu.se184491.loadmaster_be.exception;

import org.springframework.http.HttpStatus;

public class InsufficientCreditsException extends RuntimeException {
    public InsufficientCreditsException() {
        super("Tài khoản doanh nghiệp không đủ credit để thực hiện tối ưu xếp hàng 3D");
    }

    public InsufficientCreditsException(Long companyId, int balance) {
        super(String.format("Doanh nghiệp id=%d không đủ credit (số dư hiện tại: %d)", companyId, balance));
    }

    public InsufficientCreditsException(String message) {
        super(message);
    }

    public HttpStatus getHttpStatus() {
        return HttpStatus.PAYMENT_REQUIRED;
    }
}
