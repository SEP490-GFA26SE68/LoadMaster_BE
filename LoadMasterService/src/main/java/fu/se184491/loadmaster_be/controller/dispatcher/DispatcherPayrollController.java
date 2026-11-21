package fu.se184491.loadmaster_be.controller.dispatcher;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherPayrollStatementResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.payroll.DispatcherPayrollService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dispatcher/payroll")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DISPATCHER')")
public class DispatcherPayrollController {

    private final DispatcherPayrollService payrollService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public ApiResponse<DispatcherPayrollStatementResponse> getStatement(@RequestParam String period) {
        User dispatcher = currentUserService.getCurrentUser();
        Long companyId = currentUserService.getCurrentCompanyId();
        return ApiResponse.success(payrollService.getDispatcherStatement(companyId, dispatcher.getId(), period));
    }
}
