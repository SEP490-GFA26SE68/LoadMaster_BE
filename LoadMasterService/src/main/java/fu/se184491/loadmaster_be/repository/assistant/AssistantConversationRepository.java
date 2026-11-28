package fu.se184491.loadmaster_be.repository.assistant;

import fu.se184491.loadmaster_be.entity.assistant.AssistantConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, Long> {

    Optional<AssistantConversation> findByPublicId(String publicId);

    /** Ghi mã yêu cầu hỗ trợ khi hội thoại chưa có; trả 0 khi đã có (mỗi hội thoại một phiếu). Gọi trong giao dịch. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AssistantConversation c set c.handoffTicketCode = :code "
            + "where c.id = :id and c.handoffTicketCode is null")
    int claimHandoffTicket(@Param("id") Long id, @Param("code") String code);
}
