package fu.se184491.loadmaster_be.exception;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidTripStatusTransitionException extends RuntimeException {

    public InvalidTripStatusTransitionException(TripStatus currentStatus, TripStatus newStatus) {
        super("Cannot transition trip from " + currentStatus + " to " + newStatus);
    }
}
