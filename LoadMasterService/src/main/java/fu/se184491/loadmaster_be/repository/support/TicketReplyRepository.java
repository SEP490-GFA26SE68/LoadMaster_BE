package fu.se184491.loadmaster_be.repository.support;

import fu.se184491.loadmaster_be.entity.support.TicketReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Repository
public interface TicketReplyRepository extends JpaRepository<TicketReply, Long> {
    List<TicketReply> findByTicketIdOrderByCreatedAtAscIdAsc(Long ticketId);

    /** Những phiếu trong danh sách đã có trả lời ở bảng — một lượt đọc cho cả danh sách. */
    @Query("select distinct r.ticket.id from TicketReply r where r.ticket.id in :ticketIds")
    Set<Long> findTicketIdsWithReplies(@Param("ticketIds") Collection<Long> ticketIds);

    /** Xoá mọi trả lời của các phiếu: chạy trước khi xoá phiếu (khoá ngoại) và khi hết hạn giữ lịch sử trao đổi. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TicketReply r where r.ticket.id in :ticketIds")
    int deleteByTicketIdIn(@Param("ticketIds") Collection<Long> ticketIds);
}
