package fu.se184491.loadmaster_be.repository.billing;

import fu.se184491.loadmaster_be.entity.billing.CreditAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CreditAccountRepository extends JpaRepository<CreditAccount, Long> {

    Optional<CreditAccount> findByCompanyId(Long companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ca FROM CreditAccount ca WHERE ca.company.id = :companyId")
    Optional<CreditAccount> findByCompanyIdForUpdate(@Param("companyId") Long companyId);

    boolean existsByCompanyId(Long companyId);
}
