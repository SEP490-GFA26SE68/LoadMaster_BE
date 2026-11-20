package fu.se184491.loadmaster_be.service.payroll;

import fu.se184491.loadmaster_be.dto.request.payroll.CompanyDispatcherPayrollRateRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.DispatcherBaseSalaryRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.DispatcherPayrollLineRequest;
import fu.se184491.loadmaster_be.dto.response.payroll.CompanyDispatcherPayrollRateResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherBaseSalaryResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherPayrollPeriodResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherPayrollStatementResponse;

import java.util.List;

public interface DispatcherPayrollService {

    List<CompanyDispatcherPayrollRateResponse> getRates(Long companyId);

    CompanyDispatcherPayrollRateResponse createRate(Long companyId, CompanyDispatcherPayrollRateRequest request);

    DispatcherBaseSalaryResponse setDispatcherBaseSalary(Long companyId, Long dispatcherId, DispatcherBaseSalaryRequest request);

    DispatcherPayrollPeriodResponse calculatePeriod(Long companyId, String period);

    DispatcherPayrollPeriodResponse getPeriod(Long companyId, String period);

    void deleteDraftPeriod(Long companyId, String period);

    DispatcherPayrollPeriodResponse lockPeriod(Long companyId, String period);

    DispatcherPayrollPeriodResponse updateLine(
            Long companyId, String period, Long dispatcherId, DispatcherPayrollLineRequest request);

    DispatcherPayrollStatementResponse getCompanyDispatcherStatement(Long companyId, Long dispatcherId, String period);

    DispatcherPayrollStatementResponse getDispatcherStatement(Long companyId, Long dispatcherId, String period);
}
