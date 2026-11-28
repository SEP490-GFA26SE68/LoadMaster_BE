package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
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
    // Match Python approval's ascending Plan locks, including writers that do not hold Trip.
    @Query(value = """
            WITH locked_plans AS MATERIALIZED (
                SELECT p.id FROM load_plans p
                WHERE p.job_id IN (SELECT j.id FROM optimization_jobs j WHERE j.trip_id = :tripId)
                ORDER BY p.id FOR UPDATE
            )
            UPDATE load_plans p SET is_stale = true, is_approved = false
            FROM locked_plans locked WHERE p.id = locked.id
            """, nativeQuery = true)
    int markStaleByTripId(@Param("tripId") Long tripId);

    /**
     * Mã các chuyến của công ty ở trạng thái {@code tripStatus} có phương án còn hiệu lực mà chưa phương án nào được
     * duyệt. Chỉ đọc.
     */
    @Query("""
        SELECT DISTINCT t.tripCode
        FROM LoadPlan p
        JOIN p.job j
        JOIN j.trip t
        WHERE t.company.id = :companyId
          AND t.status = :tripStatus
          AND (p.stale IS NULL OR p.stale = false)
          AND NOT EXISTS (
              SELECT 1 FROM LoadPlan q
              WHERE q.job.trip = t
                AND q.approved = true)
        """)
    List<String> findTripCodesAwaitingApproval(
            @Param("companyId") Long companyId,
            @Param("tripStatus") TripStatus tripStatus
    );

    /**
     * Mã các chuyến của công ty ở trạng thái {@code tripStatus} có phương án lỗi thời và không còn phương án nào còn
     * hiệu lực. Chỉ đọc.
     */
    @Query("""
        SELECT DISTINCT t.tripCode
        FROM LoadPlan p
        JOIN p.job j
        JOIN j.trip t
        WHERE t.company.id = :companyId
          AND t.status = :tripStatus
          AND p.stale = true
          AND NOT EXISTS (
              SELECT 1 FROM LoadPlan q
              WHERE q.job.trip = t
                AND (q.stale IS NULL OR q.stale = false))
        """)
    List<String> findTripCodesWithOnlyStalePlans(
            @Param("companyId") Long companyId,
            @Param("tripStatus") TripStatus tripStatus
    );
}
