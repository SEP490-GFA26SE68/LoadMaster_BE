package fu.se184491.loadmaster_be.repository.support;

import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import fu.se184491.loadmaster_be.entity.support.TrialAccountRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface TrialAccountRequestRepository extends JpaRepository<TrialAccountRequest, Long>, JpaSpecificationExecutor<TrialAccountRequest> {
    Optional<TrialAccountRequest> findByContact_Id(Long contactId);
    Page<TrialAccountRequest> findByStatus(TrialRequestStatus status, Pageable pageable);

    boolean existsByTaxCodeAndStatusIn(String taxCode, Collection<TrialRequestStatus> statuses);
    boolean existsByTaxCodeAndStatusInAndIdNot(String taxCode, Collection<TrialRequestStatus> statuses, Long id);
    boolean existsByEmailAndStatusIn(String email, Collection<TrialRequestStatus> statuses);
    boolean existsByEmailAndStatusInAndIdNot(String email, Collection<TrialRequestStatus> statuses, Long id);
    Optional<TrialAccountRequest> findFirstByTaxCodeOrderByCreatedAtDesc(String taxCode);
    Optional<TrialAccountRequest> findFirstByEmailOrderByCreatedAtDesc(String email);
}
