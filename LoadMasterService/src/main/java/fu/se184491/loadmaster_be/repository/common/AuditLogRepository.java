package fu.se184491.loadmaster_be.repository.common;

import fu.se184491.loadmaster_be.entity.common.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
