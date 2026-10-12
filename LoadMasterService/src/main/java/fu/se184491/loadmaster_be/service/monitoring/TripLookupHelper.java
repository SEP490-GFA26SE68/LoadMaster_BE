package fu.se184491.loadmaster_be.service.monitoring;

import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TripLookupHelper {

    private final TripRepository tripRepository;

    /**
     * Resolve path {id} as numeric PK first, then tripCode within company scope.
     */
    public Trip requireTrip(String idOrCode, Long companyId) {
        if (idOrCode == null || idOrCode.isBlank()) {
            throw new AppException(ErrorCode.TRIP_NOT_FOUND);
        }
        String trimmed = idOrCode.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            try {
                Long id = Long.parseLong(trimmed);
                return tripRepository.findByIdAndCompanyId(id, companyId)
                        .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
            } catch (NumberFormatException ignored) {
                // fall through to tripCode
            }
        }
        return tripRepository.findByTripCodeAndCompanyId(trimmed, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
    }

    public Trip requireTripById(Long tripId) {
        return tripRepository.findById(tripId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
    }

    /**
     * Resolve trip for driver GPS ingest (no company scope): numeric PK first, then tripCode.
     */
    public Trip requireTripByIdOrCode(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            throw new AppException(ErrorCode.TRIP_NOT_FOUND);
        }
        String trimmed = idOrCode.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            try {
                return tripRepository.findById(Long.parseLong(trimmed))
                        .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return tripRepository.findByTripCode(trimmed)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
    }
}
