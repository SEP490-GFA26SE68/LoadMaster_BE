package fu.se184491.loadmaster_be.service.cargo;

import fu.se184491.loadmaster_be.dto.request.cargo.StackingRuleRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.StackingRuleResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StackingRuleService {

    StackingRuleResponse createStackingRule(Long companyId, StackingRuleRequest request);

    StackingRuleResponse updateStackingRule(Long companyId, Long id, StackingRuleRequest request);

    StackingRuleResponse getStackingRuleById(Long companyId, Long id);

    void deleteStackingRule(Long companyId, Long id);

    Page<StackingRuleResponse> getAllStackingRules(Long companyId, Pageable pageable);

    Boolean isStackingAllowed(Long companyId, Long bottomPackageTypeId, Long topPackageTypeId);
}
