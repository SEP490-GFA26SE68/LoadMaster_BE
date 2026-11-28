package fu.se184491.loadmaster_be.service.payroll;

import fu.se184491.loadmaster_be.dto.request.payroll.CompanyPayrollRateRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.DriverBaseSalaryRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.DriverPayrollPenaltyRequest;
import fu.se184491.loadmaster_be.dto.request.payroll.SetTripPayrollDriversRequest;
import fu.se184491.loadmaster_be.dto.response.payroll.CompanyPayrollRateResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.DriverBaseSalaryResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollPeriodResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollStatementResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.TripPayrollDriverResponse;

import java.util.List;

public interface DriverPayrollService {

    List<CompanyPayrollRateResponse> getRates(Long companyId);

    CompanyPayrollRateResponse createRate(Long companyId, CompanyPayrollRateRequest request);

    DriverBaseSalaryResponse setDriverBaseSalary(Long companyId, Long driverId, DriverBaseSalaryRequest request);

    List<TripPayrollDriverResponse> getTripDrivers(Long companyId, Long tripId);

    List<TripPayrollDriverResponse> setTripDrivers(Long companyId, Long tripId, SetTripPayrollDriversRequest request);

    PayrollPeriodResponse calculatePeriod(Long companyId, String period);

    PayrollPeriodResponse getPeriod(Long companyId, String period);

    void deleteDraftPeriod(Long companyId, String period);

    PayrollStatementResponse getCompanyDriverStatement(Long companyId, Long driverId, String period);

    PayrollStatementResponse getDriverStatement(Long companyId, Long driverId, String period);

    PayrollPeriodResponse lockPeriod(Long companyId, String period);

    PayrollPeriodResponse unlockPeriod(Long companyId, String period);

    /** Tiền phạt quản lý nhập tay cho một tài xế trong tháng (0 = bỏ phạt); chỉ bảng nháp. */
    PayrollPeriodResponse setDriverPenalty(Long companyId, String period, Long driverId, DriverPayrollPenaltyRequest request);
}
