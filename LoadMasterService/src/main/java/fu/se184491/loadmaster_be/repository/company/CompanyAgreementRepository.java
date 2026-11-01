package fu.se184491.loadmaster_be.repository.company;

import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;
import org.springframework.data.jpa.repository.JpaRepository;
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

    boolean existsByCompanyIdAndStatus(Long companyId, AgreementStatus status);
}
