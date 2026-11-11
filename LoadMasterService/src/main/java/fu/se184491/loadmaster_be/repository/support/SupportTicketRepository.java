package fu.se184491.loadmaster_be.repository.support;

import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long>, JpaSpecificationExecutor<SupportTicket> {
    List<SupportTicket> findByRequesterCompanyIdOrderByCreatedAtDesc(Long companyId);

    List<SupportTicket> findByStatusOrderByCreatedAtDesc(TicketStatus status);

    List<SupportTicket> findByCategoryOrderByCreatedAtDesc(TicketCategory category);

    /** Phiếu ở các trạng thái cho trước còn giữ lịch sử trao đổi — đầu vào của việc dọn lịch sử định kỳ. */
    List<SupportTicket> findByStatusInAndResolutionNoteIsNotNull(Collection<TicketStatus> statuses);
}
