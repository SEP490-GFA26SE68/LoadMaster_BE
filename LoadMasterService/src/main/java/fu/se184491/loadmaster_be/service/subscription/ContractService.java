package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.dto.request.subscription.CreateContractRequest;
import fu.se184491.loadmaster_be.dto.request.subscription.UpdateContractRequest;
import fu.se184491.loadmaster_be.dto.response.subscription.ContractResponse;

import java.util.List;

public interface ContractService {

    List<ContractResponse> getContracts(Long companyId, SubscriptionStatus status, Long planId);

    ContractResponse getContractById(Long id);

    ContractResponse createOrActivateContract(CreateContractRequest request);

    ContractResponse updateContract(Long id, UpdateContractRequest request);
}
