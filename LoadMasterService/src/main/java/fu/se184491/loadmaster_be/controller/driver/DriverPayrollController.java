package fu.se184491.loadmaster_be.controller.driver;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollStatementResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.payroll.DriverPayrollService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/driver/payroll")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DRIVER')")
public class DriverPayrollController {

    private final DriverPayrollService payrollService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public ApiResponse<PayrollStatementResponse> getStatement(@RequestParam String period) {
        User driver = currentUserService.getCurrentUser();
        Long companyId = currentUserService.getCurrentCompanyId();
        return ApiResponse.success(payrollService.getDriverStatement(companyId, driver.getId(), period));
    }
}
