package fu.se184491.loadmaster_be.repository.warehouse;

import fu.se184491.loadmaster_be.entity.warehouse.PlacementConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PlacementConfirmationRepository extends JpaRepository<PlacementConfirmation, Long> {

    Optional<PlacementConfirmation> findByPlacementId(Long placementId);

    List<PlacementConfirmation> findByPlacementLoadPlanId(Long loadPlanId);

    boolean existsByPlacementId(Long placementId);

    void deleteByPlacementId(Long placementId);

    /**
     * Xác nhận xếp còn hiệu lực của nhân viên kho một công ty trong khoảng [start, end) — nguồn của lương kho.
     * Xác nhận tay đang chờ điều phối duyệt chưa tính; bị từ chối thì dòng xác nhận đã bị gỡ khi từ chối (điều kiện REJECTED dưới đây chỉ
     * là lớp chặn thêm cho dòng cũ còn sót). Chuyến và nhân viên đều phải thuộc công ty.
     */
    @Query("""
            select c from PlacementConfirmation c
              join fetch c.confirmedBy w
              join fetch c.placement p
              join fetch p.cargoPackage pkg
              left join fetch pkg.packageType
              join fetch p.loadPlan lp
              join fetch lp.job j
              join fetch j.trip t
             where t.company.id = :companyId
               and w.company.id = :companyId
               and w.userRoleType = fu.se184491.loadmaster_be.constant.account.UserRole.WAREHOUSE_WORKER
               and c.confirmedAt >= :start and c.confirmedAt < :end
               and not exists (
                   select 1 from ManualUnloadConfirmation m
                    where m.placement = p
                      and m.step = fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep.LOADING
                      and (m.status = fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus.PENDING
                           or (m.status = fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus.REJECTED
                               and m.requestedAt >= c.confirmedAt)))
             order by w.id, t.id, c.id
            """)
    List<PlacementConfirmation> findValidWarehouseLoadings(
            @Param("companyId") Long companyId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );
}
