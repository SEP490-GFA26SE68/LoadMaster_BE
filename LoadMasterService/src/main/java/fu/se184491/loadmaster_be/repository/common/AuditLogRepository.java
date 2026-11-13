package fu.se184491.loadmaster_be.repository.common;

import fu.se184491.loadmaster_be.entity.common.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    /**
     * Nhật ký về một kiện của công ty: bản ghi có đối tượng là chính kiện, hoặc mang {@code packageId} của kiện trong giá trị mới
     * (soạn, báo thiếu, kiện hỏng, xác nhận tay, sự cố giao…). Mới nhất trước.
     */
    @Query(value = "SELECT * FROM audit_logs a WHERE a.company_id = :companyId AND ("
            + "(a.entity_name = 'CargoPackage' AND a.entity_id = :packageId)"
            + " OR a.new_values ->> 'packageId' = :packageId)"
            + " ORDER BY a.created_at DESC, a.id DESC LIMIT :limit", nativeQuery = true)
    List<AuditLog> findPackageHistory(@Param("companyId") Long companyId, @Param("packageId") String packageId,
                                      @Param("limit") int limit);
}
