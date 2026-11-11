package fu.se184491.loadmaster_be.repository.company;

import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyAgreementRepository extends JpaRepository<CompanyAgreement, Long> {

    Optional<CompanyAgreement> findByCompanyId(Long companyId);

    Optional<CompanyAgreement> findByCompanyIdAndStatus(Long companyId, AgreementStatus status);

    Optional<CompanyAgreement> findFirstByCompanyIdOrderByCreatedAtDesc(Long companyId);

    List<CompanyAgreement> findByStatusAndDeadlineAtBefore(AgreementStatus status, LocalDateTime deadline);

    /** Chỉ lấy mã, để job xử lý từng thỏa thuận trong transaction riêng của nó. */
    @Query("select a.id from CompanyAgreement a where a.status = :status and a.deadlineAt < :before order by a.id")
    List<Long> findIdsByStatusAndDeadlineAtBefore(
            @Param("status") AgreementStatus status,
            @Param("before") LocalDateTime before);

    @Query("select a.id from CompanyAgreement a where a.status = :status and a.expiredAt < :before order by a.id")
    List<Long> findIdsByStatusAndExpiredAtBefore(
            @Param("status") AgreementStatus status,
            @Param("before") LocalDateTime before);

    /** Điền mốc hết hạn cho bản ghi có trước cột expired_at, để mốc 30 ngày xóa cứng bắt đầu từ lần chạy này. */
    @Modifying
    @Query("update CompanyAgreement a set a.expiredAt = :expiredAt where a.status = :status and a.expiredAt is null")
    int fillMissingExpiredAt(
            @Param("status") AgreementStatus status,
            @Param("expiredAt") LocalDateTime expiredAt);

    boolean existsByCompanyIdAndStatus(Long companyId, AgreementStatus status);
}
