package fu.se184491.loadmaster_be.repository.cargo;

import fu.se184491.loadmaster_be.entity.optimization.StackingRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StackingRuleRepository extends JpaRepository<StackingRule, Long>, JpaSpecificationExecutor<StackingRule> {

    Page<StackingRule> findByCompanyId(Long companyId, Pageable pageable);

    List<StackingRule> findByCompanyId(Long companyId);

    Optional<StackingRule> findByCompanyIdAndBottomTypeIdAndTopTypeId(Long companyId, Long bottomTypeId, Long topTypeId);

    boolean existsByCompanyIdAndBottomTypeIdAndTopTypeId(Long companyId, Long bottomTypeId, Long topTypeId);

    boolean existsByCompanyIdAndBottomTypeIdAndTopTypeIdAndIdNot(Long companyId, Long bottomTypeId, Long topTypeId, Long id);
}
