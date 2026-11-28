package fu.se184491.loadmaster_be.controller.warehouse;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollStatementResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.payroll.WarehousePayrollService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Phiếu lương của chính nhân viên kho đang đăng nhập; kỳ chưa chốt chỉ có lương cứng. */
@RestController
@RequestMapping("/api/warehouse/payroll")
@RequiredArgsConstructor
@PreAuthorize("hasRole('WAREHOUSE_WORKER')")
public class WarehousePayrollController {

    private final WarehousePayrollService payrollService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public ApiResponse<WarehousePayrollStatementResponse> getStatement(@RequestParam String period) {
        User worker = currentUserService.getCurrentUser();
        Long companyId = currentUserService.getCurrentCompanyId();
        return ApiResponse.success(payrollService.getWorkerStatement(companyId, worker.getId(), period));
    }
}
