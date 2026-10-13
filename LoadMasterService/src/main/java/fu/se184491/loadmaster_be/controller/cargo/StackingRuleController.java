package fu.se184491.loadmaster_be.controller.cargo;

import fu.se184491.loadmaster_be.dto.request.cargo.StackingRuleRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.StackingRuleResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.cargo.StackingRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stacking-rules")
@RequiredArgsConstructor
public class StackingRuleController {

    private final StackingRuleService stackingRuleService;

    @Autowired(required = false)
    private CurrentUserService currentUserService;

    private Long getCurrentCompanyId() {
        try {
            if (currentUserService != null) {
                return currentUserService.getCurrentCompanyId();
            }
        } catch (Exception ignored) {
        }
        return 1L;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('PACKAGE_TYPE_MANAGE', 'DISPATCHER')")
    public StackingRuleResponse createStackingRule(@Valid @RequestBody StackingRuleRequest request) {
        return stackingRuleService.createStackingRule(getCurrentCompanyId(), request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PACKAGE_TYPE_MANAGE', 'DISPATCHER')")
    public StackingRuleResponse updateStackingRule(@PathVariable Long id, @Valid @RequestBody StackingRuleRequest request) {
        return stackingRuleService.updateStackingRule(getCurrentCompanyId(), id, request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PACKAGE_TYPE_MANAGE', 'DISPATCHER')")
    public StackingRuleResponse getStackingRuleById(@PathVariable Long id) {
        return stackingRuleService.getStackingRuleById(getCurrentCompanyId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('PACKAGE_TYPE_MANAGE', 'DISPATCHER')")
    public void deleteStackingRule(@PathVariable Long id) {
        stackingRuleService.deleteStackingRule(getCurrentCompanyId(), id);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PACKAGE_TYPE_MANAGE', 'DISPATCHER')")
    public Page<StackingRuleResponse> getAllStackingRules(Pageable pageable) {
        return stackingRuleService.getAllStackingRules(getCurrentCompanyId(), pageable);
    }

    @GetMapping("/check")
    @PreAuthorize("hasAnyAuthority('PACKAGE_TYPE_MANAGE', 'DISPATCHER')")
    public Boolean checkStackingAllowed(
            @RequestParam Long bottomPackageTypeId,
            @RequestParam Long topPackageTypeId) {
        return stackingRuleService.isStackingAllowed(getCurrentCompanyId(), bottomPackageTypeId, topPackageTypeId);
    }
}
