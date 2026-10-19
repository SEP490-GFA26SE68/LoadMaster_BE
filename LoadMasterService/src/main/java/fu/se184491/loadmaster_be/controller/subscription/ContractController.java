package fu.se184491.loadmaster_be.controller.subscription;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.dto.request.subscription.CreateContractRequest;
import fu.se184491.loadmaster_be.dto.request.subscription.UpdateContractRequest;
import fu.se184491.loadmaster_be.dto.response.subscription.ContractResponse;
import fu.se184491.loadmaster_be.service.subscription.ContractService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAnyAuthority('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
public class ContractController {

    private final ContractService contractService;

    @GetMapping
    public ResponseEntity<List<ContractResponse>> getContracts(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) SubscriptionStatus status,
            @RequestParam(required = false) Long planId
    ) {
        List<ContractResponse> contracts = contractService.getContracts(companyId, status, planId);
        return ResponseEntity.ok(contracts);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ContractResponse> getContractById(@PathVariable Long id) {
        ContractResponse contract = contractService.getContractById(id);
        return ResponseEntity.ok(contract);
    }

    @PostMapping
    public ResponseEntity<ContractResponse> createOrActivateContract(
            @Valid @RequestBody CreateContractRequest request
    ) {
        ContractResponse contract = contractService.createOrActivateContract(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(contract);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ContractResponse> updateContract(
            @PathVariable Long id,
            @RequestBody UpdateContractRequest request
    ) {
        ContractResponse contract = contractService.updateContract(id, request);
        return ResponseEntity.ok(contract);
    }
}
