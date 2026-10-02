package fu.se184491.loadmaster_be.exception;

public class RouteOptimizationUnavailableException extends RuntimeException {

    public RouteOptimizationUnavailableException(Throwable cause) {
        super("Route optimization is unavailable because the map provider could not be reached", cause);
    }
}
