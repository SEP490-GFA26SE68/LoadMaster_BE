package fu.se184491.loadmaster_be.controller.company;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.payroll.CompanyDispatcherPayrollRateRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.DispatcherBaseSalaryRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.DispatcherPayrollLineRequest;
import fu.se184491.loadmaster_be.dto.response.payroll.CompanyDispatcherPayrollRateResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherBaseSalaryResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherPayrollPeriodResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherPayrollStatementResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.payroll.DispatcherPayrollService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/company/dispatcher-payroll")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MANAGER')")
public class CompanyDispatcherPayrollController {

    private final DispatcherPayrollService payrollService;
    private final CurrentUserService currentUserService;

    @GetMapping("/settings")
    public ApiResponse<List<CompanyDispatcherPayrollRateResponse>> getRates() {
        return ApiResponse.success(payrollService.getRates(currentUserService.getCurrentCompanyId()));
    }

    @PutMapping("/settings")
    public ApiResponse<CompanyDispatcherPayrollRateResponse> createRate(
            @Valid @RequestBody CompanyDispatcherPayrollRateRequest request) {
        return ApiResponse.success(payrollService.createRate(currentUserService.getCurrentCompanyId(), request));
    }

    @PutMapping("/dispatchers/{dispatcherId}/base-salary")
    public ApiResponse<DispatcherBaseSalaryResponse> setDispatcherBaseSalary(
            @PathVariable Long dispatcherId, @Valid @RequestBody DispatcherBaseSalaryRequest request) {
        return ApiResponse.success(payrollService.setDispatcherBaseSalary(
                currentUserService.getCurrentCompanyId(), dispatcherId, request));
    }

    @PostMapping("/periods/{period}/calculate")
    public ApiResponse<DispatcherPayrollPeriodResponse> calculatePeriod(@PathVariable String period) {
        return ApiResponse.success(payrollService.calculatePeriod(currentUserService.getCurrentCompanyId(), period));
    }

    @GetMapping("/periods/{period}")
    public ApiResponse<DispatcherPayrollPeriodResponse> getPeriod(@PathVariable String period) {
        return ApiResponse.success(payrollService.getPeriod(currentUserService.getCurrentCompanyId(), period));
    }

    @DeleteMapping("/periods/{period}")
    public ApiResponse<Void> deleteDraftPeriod(@PathVariable String period) {
        payrollService.deleteDraftPeriod(currentUserService.getCurrentCompanyId(), period);
        return ApiResponse.success("Đã xóa bảng lương điều phối nháp");
    }

    @PostMapping("/periods/{period}/lock")
    public ApiResponse<DispatcherPayrollPeriodResponse> lockPeriod(@PathVariable String period) {
        return ApiResponse.success(payrollService.lockPeriod(currentUserService.getCurrentCompanyId(), period));
    }

    @PostMapping("/periods/{period}/unlock")
    public ApiResponse<DispatcherPayrollPeriodResponse> unlockPeriod(@PathVariable String period) {
        return ApiResponse.success(payrollService.unlockPeriod(currentUserService.getCurrentCompanyId(), period));
    }

    @PutMapping("/periods/{period}/dispatchers/{dispatcherId}")
    public ApiResponse<DispatcherPayrollPeriodResponse> updateLine(
            @PathVariable String period,
            @PathVariable Long dispatcherId,
            @Valid @RequestBody DispatcherPayrollLineRequest request
    ) {
        return ApiResponse.success(payrollService.updateLine(
                currentUserService.getCurrentCompanyId(), period, dispatcherId, request));
    }

    @GetMapping("/periods/{period}/dispatchers/{dispatcherId}")
    public ApiResponse<DispatcherPayrollStatementResponse> getDispatcherStatement(
            @PathVariable String period,
            @PathVariable Long dispatcherId
    ) {
        return ApiResponse.success(payrollService.getCompanyDispatcherStatement(
                currentUserService.getCurrentCompanyId(), dispatcherId, period));
    }
}
