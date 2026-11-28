package fu.se184491.loadmaster_be.controller.company;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.payroll.CompanyWarehousePayrollRateRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.WarehouseBaseSalaryRequest;
import fu.se184491.loadmaster_be.dto.response.payroll.CompanyWarehousePayrollRateResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehouseBaseSalaryResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollPeriodResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollStatementResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.payroll.WarehousePayrollService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Bảng lương nhân viên kho của công ty, cho quản lý công ty. Mọi thao tác theo công ty của người đang đăng nhập. */
@RestController
@RequestMapping("/api/company/warehouse-payroll")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MANAGER')")
public class CompanyWarehousePayrollController {

    private final WarehousePayrollService payrollService;
    private final CurrentUserService currentUserService;

    @GetMapping("/settings")
    public ApiResponse<List<CompanyWarehousePayrollRateResponse>> getRates() {
        return ApiResponse.success(payrollService.getRates(currentUserService.getCurrentCompanyId()));
    }

    @PutMapping("/settings")
    public ApiResponse<CompanyWarehousePayrollRateResponse> createRate(
            @Valid @RequestBody CompanyWarehousePayrollRateRequest request) {
        return ApiResponse.success(payrollService.createRate(currentUserService.getCurrentCompanyId(), request));
    }

    @PutMapping("/workers/{workerId}/base-salary")
    public ApiResponse<WarehouseBaseSalaryResponse> setWorkerBaseSalary(
            @PathVariable Long workerId, @Valid @RequestBody WarehouseBaseSalaryRequest request) {
        return ApiResponse.success(payrollService.setWorkerBaseSalary(
                currentUserService.getCurrentCompanyId(), workerId, request));
    }

    @PostMapping("/periods/{period}/calculate")
    public ApiResponse<WarehousePayrollPeriodResponse> calculatePeriod(@PathVariable String period) {
        return ApiResponse.success(payrollService.calculatePeriod(currentUserService.getCurrentCompanyId(), period));
    }

    @GetMapping("/periods/{period}")
    public ApiResponse<WarehousePayrollPeriodResponse> getPeriod(@PathVariable String period) {
        return ApiResponse.success(payrollService.getPeriod(currentUserService.getCurrentCompanyId(), period));
    }

    @DeleteMapping("/periods/{period}")
    public ApiResponse<Void> deleteDraftPeriod(@PathVariable String period) {
        payrollService.deleteDraftPeriod(currentUserService.getCurrentCompanyId(), period);
        return ApiResponse.success("Đã xóa bảng lương kho nháp");
    }

    @GetMapping("/periods/{period}/workers/{workerId}")
    public ApiResponse<WarehousePayrollStatementResponse> getWorkerStatement(
            @PathVariable String period,
            @PathVariable Long workerId
    ) {
        return ApiResponse.success(payrollService.getCompanyWorkerStatement(
                currentUserService.getCurrentCompanyId(), workerId, period));
    }

    @PostMapping("/periods/{period}/lock")
    public ApiResponse<WarehousePayrollPeriodResponse> lockPeriod(@PathVariable String period) {
        return ApiResponse.success(payrollService.lockPeriod(currentUserService.getCurrentCompanyId(), period));
    }

    @PostMapping("/periods/{period}/unlock")
    public ApiResponse<WarehousePayrollPeriodResponse> unlockPeriod(@PathVariable String period) {
        return ApiResponse.success(payrollService.unlockPeriod(currentUserService.getCurrentCompanyId(), period));
    }
}
