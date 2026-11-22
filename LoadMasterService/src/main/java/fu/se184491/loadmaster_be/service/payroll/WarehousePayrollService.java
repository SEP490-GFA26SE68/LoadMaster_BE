package fu.se184491.loadmaster_be.service.payroll;

import fu.se184491.loadmaster_be.dto.request.payroll.CompanyWarehousePayrollRateRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.WarehouseBaseSalaryRequest;
import fu.se184491.loadmaster_be.dto.response.payroll.CompanyWarehousePayrollRateResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehouseBaseSalaryResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollPeriodResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollStatementResponse;

import java.util.List;

/** Lương nhân viên kho: lương cứng + đơn giá × kg kiện đã xếp lên xe trong tháng + thưởng KPI theo kg. */
public interface WarehousePayrollService {

    List<CompanyWarehousePayrollRateResponse> getRates(Long companyId);

    CompanyWarehousePayrollRateResponse createRate(Long companyId, CompanyWarehousePayrollRateRequest request);

    WarehouseBaseSalaryResponse setWorkerBaseSalary(Long companyId, Long workerId, WarehouseBaseSalaryRequest request);

    WarehousePayrollPeriodResponse calculatePeriod(Long companyId, String period);

    WarehousePayrollPeriodResponse getPeriod(Long companyId, String period);

    void deleteDraftPeriod(Long companyId, String period);

    WarehousePayrollStatementResponse getCompanyWorkerStatement(Long companyId, Long workerId, String period);

    WarehousePayrollStatementResponse getWorkerStatement(Long companyId, Long workerId, String period);

    WarehousePayrollPeriodResponse lockPeriod(Long companyId, String period);

    WarehousePayrollPeriodResponse unlockPeriod(Long companyId, String period);
}
