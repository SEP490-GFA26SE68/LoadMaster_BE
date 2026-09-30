package fu.se184491.loadmaster_be.exception.import_;

import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;

public class UnsupportedFileTypeException extends AppException {
    public UnsupportedFileTypeException() {
        super(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    public UnsupportedFileTypeException(String message) {
        super(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }
}
