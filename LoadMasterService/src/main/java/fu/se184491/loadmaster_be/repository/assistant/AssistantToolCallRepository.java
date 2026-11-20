package fu.se184491.loadmaster_be.repository.assistant;

import fu.se184491.loadmaster_be.entity.assistant.AssistantToolCall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssistantToolCallRepository extends JpaRepository<AssistantToolCall, Long> {

    List<AssistantToolCall> findByMessageIdOrderByIdAsc(Long messageId);
}
