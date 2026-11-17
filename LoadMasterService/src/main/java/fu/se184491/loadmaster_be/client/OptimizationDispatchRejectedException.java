package fu.se184491.loadmaster_be.client;

import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;

/** This HTTP attempt was rejected before scheduling; other attempts may still own the job. */
public final class OptimizationDispatchRejectedException extends AppException {
    public OptimizationDispatchRejectedException() {
        super(ErrorCode.PACKING_PROFILE_ORIENTATION_INVALID);
    }
}
