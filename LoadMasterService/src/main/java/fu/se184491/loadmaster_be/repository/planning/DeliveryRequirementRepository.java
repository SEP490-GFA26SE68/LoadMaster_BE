package fu.se184491.loadmaster_be.repository.planning;

import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface DeliveryRequirementRepository extends JpaRepository<DeliveryRequirement, Long>,
        JpaSpecificationExecutor<DeliveryRequirement> {

    @EntityGraph(attributePaths = "packages")
    Optional<DeliveryRequirement> findByIdAndCompanyId(Long id, Long companyId);
}
