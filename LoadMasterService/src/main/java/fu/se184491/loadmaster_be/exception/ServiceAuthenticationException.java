package fu.se184491.loadmaster_be.exception;

/**
 * Thrown when Spring Boot cannot obtain a service token from Keycloak
 * (e.g., Keycloak is down, client secret wrong, network error).
 *
 * This is a RuntimeException so callers don't need checked-exception handling;
 * {@link GlobalExceptionHandler} maps it to HTTP 503 Service Unavailable.
 */
public class ServiceAuthenticationException extends RuntimeException {

    public ServiceAuthenticationException(String message) {
        super(message);
    }

    public ServiceAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
