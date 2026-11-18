package fu.se184491.loadmaster_be.controller.company;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.payroll.CompanyPayrollRateRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.DriverBaseSalaryRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.SetTripPayrollDriversRequest;
import fu.se184491.loadmaster_be.dto.response.payroll.CompanyPayrollRateResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DriverBaseSalaryResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollPeriodResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollStatementResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.TripPayrollDriverResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.payroll.DriverPayrollService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/company/payroll")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MANAGER')")
public class CompanyPayrollController {

    private final DriverPayrollService payrollService;
    private final CurrentUserService currentUserService;

    @GetMapping("/settings")
    public ApiResponse<List<CompanyPayrollRateResponse>> getRates() {
        return ApiResponse.success(payrollService.getRates(currentUserService.getCurrentCompanyId()));
    }

    @PutMapping("/settings")
    public ApiResponse<CompanyPayrollRateResponse> createRate(@Valid @RequestBody CompanyPayrollRateRequest request) {
        return ApiResponse.success(payrollService.createRate(currentUserService.getCurrentCompanyId(), request));
    }

    @PutMapping("/drivers/{driverId}/base-salary")
    public ApiResponse<DriverBaseSalaryResponse> setDriverBaseSalary(
            @PathVariable Long driverId, @Valid @RequestBody DriverBaseSalaryRequest request) {
        return ApiResponse.success(payrollService.setDriverBaseSalary(
                currentUserService.getCurrentCompanyId(), driverId, request));
    }

    @PutMapping("/trips/{tripId}/drivers")
    public ApiResponse<List<TripPayrollDriverResponse>> setTripDrivers(
            @PathVariable Long tripId,
            @Valid @RequestBody SetTripPayrollDriversRequest request
    ) {
        return ApiResponse.success(payrollService.setTripDrivers(
                currentUserService.getCurrentCompanyId(), tripId, request));
    }

    @GetMapping("/trips/{tripId}/drivers")
    public ApiResponse<List<TripPayrollDriverResponse>> getTripDrivers(@PathVariable Long tripId) {
        return ApiResponse.success(payrollService.getTripDrivers(currentUserService.getCurrentCompanyId(), tripId));
    }

    @PostMapping("/periods/{period}/calculate")
    public ApiResponse<PayrollPeriodResponse> calculatePeriod(@PathVariable String period) {
        return ApiResponse.success(payrollService.calculatePeriod(currentUserService.getCurrentCompanyId(), period));
    }

    @GetMapping("/periods/{period}")
    public ApiResponse<PayrollPeriodResponse> getPeriod(@PathVariable String period) {
        return ApiResponse.success(payrollService.getPeriod(currentUserService.getCurrentCompanyId(), period));
    }

    @DeleteMapping("/periods/{period}")
    public ApiResponse<Void> deleteDraftPeriod(@PathVariable String period) {
        payrollService.deleteDraftPeriod(currentUserService.getCurrentCompanyId(), period);
        return ApiResponse.success("Đã xóa bảng lương nháp");
    }

    @GetMapping("/periods/{period}/drivers/{driverId}")
    public ApiResponse<PayrollStatementResponse> getDriverStatement(
            @PathVariable String period,
            @PathVariable Long driverId
    ) {
        return ApiResponse.success(payrollService.getCompanyDriverStatement(
                currentUserService.getCurrentCompanyId(), driverId, period));
    }

    @PostMapping("/periods/{period}/lock")
    public ApiResponse<PayrollPeriodResponse> lockPeriod(@PathVariable String period) {
        return ApiResponse.success(payrollService.lockPeriod(currentUserService.getCurrentCompanyId(), period));
    }
}
