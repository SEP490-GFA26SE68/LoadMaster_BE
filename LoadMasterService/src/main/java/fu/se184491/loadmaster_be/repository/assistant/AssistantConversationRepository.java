package fu.se184491.loadmaster_be.repository.assistant;

import fu.se184491.loadmaster_be.entity.assistant.AssistantConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, Long> {

    Optional<AssistantConversation> findByPublicId(String publicId);
}
