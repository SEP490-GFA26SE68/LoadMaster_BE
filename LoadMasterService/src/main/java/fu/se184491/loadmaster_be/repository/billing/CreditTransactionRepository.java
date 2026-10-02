package fu.se184491.loadmaster_be.repository.billing;

import fu.se184491.loadmaster_be.constant.billing.CreditTransactionType;
import fu.se184491.loadmaster_be.entity.billing.CreditTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, Long> {
    Page<CreditTransaction> findByCreditAccountIdOrderByCreatedAtDesc(Long creditAccountId, Pageable pageable);
    Optional<CreditTransaction> findByReferenceAndType(String reference, CreditTransactionType type);
}
