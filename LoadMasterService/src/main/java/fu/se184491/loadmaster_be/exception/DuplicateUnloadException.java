package fu.se184491.loadmaster_be.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateUnloadException extends RuntimeException {

    public DuplicateUnloadException(String message) {
        super(message);
    }

    public DuplicateUnloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
