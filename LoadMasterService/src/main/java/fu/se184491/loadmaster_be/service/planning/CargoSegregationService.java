package fu.se184491.loadmaster_be.service.planning;

import fu.se184491.loadmaster_be.dto.response.planning.SegregationResult;

import java.util.List;

public interface CargoSegregationService {
    SegregationResult checkTrip(Long companyId, Long tripId);

    void validateAddition(
            Long companyId,
            Long tripId,
            List<Long> packageIds,
            boolean override,
            String overrideReason
    );
}
