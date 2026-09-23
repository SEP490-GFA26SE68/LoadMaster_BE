package fu.se184491.loadmaster_be.service.optimize;

import fu.se184491.loadmaster_be.dto.response.optimization.ValidationResponse;

public interface TripValidationService {

    /**
     * Validates a trip before running the optimization job.
     *
     * @param tripId the ID of the trip to validate
     * @return ValidationResult containing canOptimize flag, errors and warnings
     * @throws fu.se184491.loadmaster_be.exception.AppException with TRIP_NOT_FOUND if trip does not exist
     */
    ValidationResponse validate(Long tripId);
}
