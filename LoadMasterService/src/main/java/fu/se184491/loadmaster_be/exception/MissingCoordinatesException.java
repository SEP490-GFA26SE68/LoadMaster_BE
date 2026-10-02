package fu.se184491.loadmaster_be.exception;

public class MissingCoordinatesException extends RuntimeException {

    public MissingCoordinatesException(Long stopId) {
        super("Delivery stop " + stopId + " is missing latitude or longitude");
    }
}
