package fu.se184491.loadmaster_be.exception;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;

/**
 * Chuyển trạng thái chuyến sai bảng. Là {@link AppException} để GlobalExceptionHandler
 * trả mã {@code INVALID_TRIP_STATUS_TRANSITION} (409) thay vì rơi vào lỗi 500 chung.
 */
public class InvalidTripStatusTransitionException extends AppException {

    public InvalidTripStatusTransitionException(TripStatus currentStatus, TripStatus newStatus) {
        super(ErrorCode.INVALID_TRIP_STATUS_TRANSITION,
                "Cannot transition trip from " + currentStatus + " to " + newStatus);
    }
}
