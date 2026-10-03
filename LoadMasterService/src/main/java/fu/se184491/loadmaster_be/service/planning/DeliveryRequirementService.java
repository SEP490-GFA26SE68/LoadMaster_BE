package fu.se184491.loadmaster_be.service.planning;

import fu.se184491.loadmaster_be.dto.request.planning.DeliveryRequirementRequest;
import fu.se184491.loadmaster_be.dto.request.planning.UpdateDeliveryRequirementRequest;
import fu.se184491.loadmaster_be.dto.response.planning.DeliveryRequirementResponse;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;

public interface DeliveryRequirementService {
    DeliveryRequirementResponse create(Long companyId, Long userId, DeliveryRequirementRequest request);

    DeliveryRequirementResponse getById(Long companyId, Long id);

    Page<DeliveryRequirementResponse> list(
            Long companyId,
            DeliveryRequirementStatus status,
            LocalDateTime deadlineFrom,
            LocalDateTime deadlineTo,
            Pageable pageable
    );

    DeliveryRequirementResponse update(
            Long companyId,
            Long id,
            UpdateDeliveryRequirementRequest request
    );

    void delete(Long companyId, Long id);

    DeliveryRequirementResponse markAssigned(Long companyId, Long id);

    DeliveryRequirementResponse markInTrip(Long companyId, Long id);
}
