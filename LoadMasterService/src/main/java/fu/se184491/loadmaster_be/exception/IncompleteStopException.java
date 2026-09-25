package fu.se184491.loadmaster_be.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class IncompleteStopException extends RuntimeException {

    public IncompleteStopException(String message) {
        super(message);
    }

    public IncompleteStopException(String message, Throwable cause) {
        super(message, cause);
    }
}
