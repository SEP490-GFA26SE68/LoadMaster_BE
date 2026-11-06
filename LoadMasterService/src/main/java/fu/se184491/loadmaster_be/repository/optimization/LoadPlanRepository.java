package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoadPlanRepository extends JpaRepository<LoadPlan, Long> {

    Optional<LoadPlan> findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(Long tripId);

    List<LoadPlan> findByJobTripCompanyIdAndApprovedTrue(Long companyId);

    @Modifying
    @Query("UPDATE LoadPlan p SET p.approved = false WHERE p.approved = true"
            + " AND p.job.id IN (SELECT j.id FROM OptimizationJob j WHERE j.trip.id = :tripId)")
    int unapproveAllByTripId(@Param("tripId") Long tripId);

    /**
     * Mọi phương án của chuyến thành lỗi thời (hàng, xe hoặc điểm giao vừa đổi) và thôi là bản duyệt: kho không xếp theo phương án
     * tính cho đầu vào cũ.
     */
    @Modifying
    @Query("UPDATE LoadPlan p SET p.stale = true, p.approved = false"
            + " WHERE p.job.id IN (SELECT j.id FROM OptimizationJob j WHERE j.trip.id = :tripId)")
    int markStaleByTripId(@Param("tripId") Long tripId);
}
