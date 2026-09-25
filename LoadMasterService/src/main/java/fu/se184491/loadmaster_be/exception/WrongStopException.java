package fu.se184491.loadmaster_be.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class WrongStopException extends RuntimeException {

    public WrongStopException(String message) {
        super(message);
    }

    public WrongStopException(String message, Throwable cause) {
        super(message, cause);
    }
}
