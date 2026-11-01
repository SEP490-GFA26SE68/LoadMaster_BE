package fu.se184491.loadmaster_be.repository.planning;

import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryRequirementRepository extends JpaRepository<DeliveryRequirement, Long>,
        JpaSpecificationExecutor<DeliveryRequirement> {

    @EntityGraph(attributePaths = "packages")
    Optional<DeliveryRequirement> findByIdAndCompanyId(Long id, Long companyId);


    @Query("""
    SELECT DISTINCT requirement
    FROM DeliveryRequirement requirement
    LEFT JOIN FETCH requirement.customer
    WHERE requirement.company.id = :companyId
      AND EXISTS (
          SELECT 1
          FROM DeliveryRequirement matchingRequirement
          JOIN matchingRequirement.packages cargoPackage
          WHERE matchingRequirement.id = requirement.id
            AND cargoPackage.id IN :packageIds
      )
    """)
    List<DeliveryRequirement> findByCompanyAndPackageIds(
            @Param("companyId") Long companyId,
            @Param("packageIds") List<Long> packageIds
    );


    /**
     * Lấy các yêu cầu giao chưa được đưa vào chuyến.
     * Chỉ trả về yêu cầu thuộc công ty hiện tại.
     */
    List<DeliveryRequirement> findByCompanyIdAndStatusAndTripIsNullOrderByDeadlineAsc(
            Long companyId,
            DeliveryRequirementStatus status
    );


    /**
     * Đếm số kiện thực tế được gán vào các yêu cầu giao
     * thuộc từng chuyến.
     *
     * Object[0]: tripId (Long)
     * Object[1]: packageCount (Long)
     */
    @Query("""
    SELECT dr.trip.id, COUNT(DISTINCT p.id)
    FROM DeliveryRequirement dr
    JOIN dr.packages p
    WHERE dr.trip.id IN :tripIds
    GROUP BY dr.trip.id
    """)
    List<Object[]> countPackagesByTripIds(
            @Param("tripIds") Collection<Long> tripIds
    );



    /**
     * Khóa yêu cầu giao khi phân chuyến.
     *
     * Ngăn hai Dispatcher đồng thời gán cùng một yêu cầu
     * vào hai Trip khác nhau.
     *
     * Phải gọi bên trong @Transactional.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT requirement
        FROM DeliveryRequirement requirement
        WHERE requirement.id = :id
          AND requirement.company.id = :companyId
        """)
    Optional<DeliveryRequirement> findByIdAndCompanyIdForUpdate(
            @Param("id") Long id,
            @Param("companyId") Long companyId
    );


    interface PackageRequirementInfo {
        Long getPackageId();
        Long getDeliveryRequirementId();
        String getCustomerName();
        Long getReceivedPackageCount();
        Integer getExpectedPackageCount();
    }


    @Query("""
    SELECT
        pkg.id AS packageId,
        requirement.id AS deliveryRequirementId,
        customer.name AS customerName,
        (
            SELECT COUNT(allPkg.id)
            FROM DeliveryRequirement countRequirement
            JOIN countRequirement.packages allPkg
            WHERE countRequirement.id = requirement.id
        ) AS receivedPackageCount,
        requirement.expectedPackageCount AS expectedPackageCount
    FROM DeliveryRequirement requirement
    JOIN requirement.packages pkg
    LEFT JOIN requirement.customer customer
    WHERE requirement.company.id = :companyId
      AND pkg.id IN :packageIds
    """)
    List<PackageRequirementInfo> findPackageRequirementInfo(
            @Param("companyId") Long companyId,
            @Param("packageIds") List<Long> packageIds
    );



}
