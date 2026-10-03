package fu.se184491.loadmaster_be.repository.planning;

import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeliveryRequirementRepository extends JpaRepository<DeliveryRequirement, Long>,
        JpaSpecificationExecutor<DeliveryRequirement> {

    @EntityGraph(attributePaths = "packages")
    Optional<DeliveryRequirement> findByIdAndCompanyId(Long id, Long companyId);

    @Query("""
            select distinct requirement
            from DeliveryRequirement requirement
            join fetch requirement.packages cargoPackage
            where requirement.company.id = :companyId
              and cargoPackage.id in :packageIds
            """)
    List<DeliveryRequirement> findByCompanyAndPackageIds(
            @Param("companyId") Long companyId,
            @Param("packageIds") List<Long> packageIds);
}
