package fu.se184491.loadmaster_be.repository.support;

import fu.se184491.loadmaster_be.entity.support.TicketReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketReplyRepository extends JpaRepository<TicketReply, Long> {
    List<TicketReply> findByTicketIdOrderByCreatedAtAscIdAsc(Long ticketId);
}
