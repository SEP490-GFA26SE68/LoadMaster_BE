package fu.se184491.loadmaster_be.exception.import_;

import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;

public class FileSizeLimitExceededException extends AppException {
    public FileSizeLimitExceededException() {
        super(ErrorCode.FILE_SIZE_LIMIT_EXCEEDED);
    }

    public FileSizeLimitExceededException(String message) {
        super(ErrorCode.FILE_SIZE_LIMIT_EXCEEDED);
    }
}
