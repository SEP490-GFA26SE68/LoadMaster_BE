package fu.se184491.loadmaster_be.exception.import_;

import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;

public class EmptyFileException extends AppException {
    public EmptyFileException() {
        super(ErrorCode.EMPTY_FILE);
    }

    public EmptyFileException(String message) {
        super(ErrorCode.EMPTY_FILE);
    }
}
