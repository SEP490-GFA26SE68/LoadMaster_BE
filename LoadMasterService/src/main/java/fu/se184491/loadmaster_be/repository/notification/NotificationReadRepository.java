package fu.se184491.loadmaster_be.repository.notification;

import fu.se184491.loadmaster_be.entity.notification.NotificationRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface NotificationReadRepository extends JpaRepository<NotificationRead, Long> {

    /** Trong {@code auditLogIds}, những dòng nhật ký người dùng đã đọc. */
    @Query("select r.auditLogId from NotificationRead r where r.userId = :userId and r.auditLogId in :auditLogIds")
    List<Long> findReadIds(@Param("userId") Long userId, @Param("auditLogIds") Collection<Long> auditLogIds);
}
