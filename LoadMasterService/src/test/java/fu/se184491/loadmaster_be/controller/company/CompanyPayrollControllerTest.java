package fu.se184491.loadmaster_be.controller.company;

import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.payroll.DriverPayrollService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyPayrollControllerTest {

    @Test
    void companyAdminCanSetNextMonthBaseSalaryForOneDriver() throws Exception {
        DriverPayrollService service = mock(DriverPayrollService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.getCurrentCompanyId()).thenReturn(10L);
        String nextMonth = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .plusMonths(1).withDayOfMonth(1).toString();

        MockMvcBuilders.standaloneSetup(new CompanyPayrollController(service, currentUser)).build()
                .perform(put("/api/company/payroll/drivers/21/base-salary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"" + nextMonth + "\",\"baseSalaryVnd\":9000000}"))
                .andExpect(status().isOk());
    }

    @Test
    void canCreatePayrollRateForNextMonth() throws Exception {
        DriverPayrollService service = mock(DriverPayrollService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.getCurrentCompanyId()).thenReturn(10L);
        String nextMonth = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .plusMonths(1).withDayOfMonth(1).toString();

        MockMvcBuilders.standaloneSetup(new CompanyPayrollController(service, currentUser)).build()
                .perform(put("/api/company/payroll/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"" + nextMonth + "\",\"baseSalaryVnd\":8000000,\"rateVndPerKg\":100,\"rateVndPerKm\":2000}"))
                .andExpect(status().isOk());
    }
}
