package fu.se184491.loadmaster_be.exception.import_;

import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;

public class BatchSizeLimitExceededException extends AppException {
    public BatchSizeLimitExceededException() {
        super(ErrorCode.BATCH_SIZE_LIMIT_EXCEEDED);
    }

    public BatchSizeLimitExceededException(String message) {
        super(ErrorCode.BATCH_SIZE_LIMIT_EXCEEDED);
    }
}
