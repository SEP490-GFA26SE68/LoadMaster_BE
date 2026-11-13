package fu.se184491.loadmaster_be.repository.support;

import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long>, JpaSpecificationExecutor<SupportTicket> {
    List<SupportTicket> findByRequesterCompanyIdOrderByCreatedAtDesc(Long companyId);

    List<SupportTicket> findByStatusOrderByCreatedAtDesc(TicketStatus status);

    List<SupportTicket> findByCategoryOrderByCreatedAtDesc(TicketCategory category);

    /**
     * Phiếu ở các trạng thái cho trước còn giữ lịch sử trao đổi — mảng cũ trong ghi chú hoặc trả lời ở bảng {@code ticket_replies} —,
     * đầu vào của việc dọn lịch sử định kỳ.
     */
    @Query("select t from SupportTicket t where t.status in :statuses and (t.resolutionNote is not null"
            + " or exists (select 1 from TicketReply r where r.ticket = t))")
    List<SupportTicket> findWithChatHistoryByStatusIn(@Param("statuses") Collection<TicketStatus> statuses);

    /** Mã các phiếu một người đã gửi — để xoá cùng tài khoản khi tài khoản bị xoá cứng. */
    @Query("select t.id from SupportTicket t where t.requester.id = :requesterId")
    List<Long> findIdsByRequesterId(@Param("requesterId") Long requesterId);
}
