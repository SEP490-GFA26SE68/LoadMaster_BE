package fu.se184491.loadmaster_be.repository.assistant;

import fu.se184491.loadmaster_be.entity.assistant.AssistantMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, Long> {

    /** Tin mới nhất trước; số tin lấy theo {@code pageable} (cấu hình {@code assistant.history-messages}). */
    List<AssistantMessage> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);

    /** Cả hội thoại theo thứ tự gửi. */
    List<AssistantMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    /** Tổng token vào + ra của mọi tin từ mốc {@code since} (ngân sách ngày). */
    @Query("select coalesce(sum(m.inputTokens + m.outputTokens), 0) from AssistantMessage m where m.createdAt >= :since")
    long sumTokensSince(@Param("since") Instant since);
}
