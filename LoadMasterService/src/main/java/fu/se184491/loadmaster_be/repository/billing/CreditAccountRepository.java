package fu.se184491.loadmaster_be.repository.billing;

import fu.se184491.loadmaster_be.entity.billing.CreditAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Tạo tài khoản credit số dư 0 cho công ty nếu chưa có. Khoá duy nhất company_id làm lượt thứ hai (kể cả hai
     * giao dịch song song) không chèn thêm dòng nào và không lỗi. Trả về số dòng đã chèn (0 hoặc 1).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO credit_accounts (company_id, balance, version) VALUES (:companyId, 0, 0)"
            + " ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfMissing(@Param("companyId") Long companyId);
}
