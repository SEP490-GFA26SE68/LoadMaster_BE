package fu.se184491.loadmaster_be.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class IncompleteLoadingException extends RuntimeException {

    public IncompleteLoadingException(String message) {
        super(message);
    }

    public IncompleteLoadingException(String message, Throwable cause) {
        super(message, cause);
    }
}
