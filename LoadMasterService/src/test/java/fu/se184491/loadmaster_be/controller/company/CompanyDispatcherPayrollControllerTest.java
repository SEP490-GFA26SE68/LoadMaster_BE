package fu.se184491.loadmaster_be.controller.company;

import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.payroll.DispatcherPayrollService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyDispatcherPayrollControllerTest {

    @Test
    void canCreateDispatcherPayrollRateForNextMonth() throws Exception {
        DispatcherPayrollService service = mock(DispatcherPayrollService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.getCurrentCompanyId()).thenReturn(10L);
        String nextMonth = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .plusMonths(1).withDayOfMonth(1).toString();

        MockMvcBuilders.standaloneSetup(new CompanyDispatcherPayrollController(service, currentUser)).build()
                .perform(put("/api/company/dispatcher-payroll/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"" + nextMonth
                                + "\",\"baseSalaryVnd\":10000000,\"overtimeVndPerHour\":50000,\"overtimeHoursPerDay\":2,\"overtimeDaysPerMonth\":4,\"kpiAmountVnd\":500000}"))
                .andExpect(status().isOk());
    }

    @Test
    void companyManagerCanSetNextMonthBaseSalaryForOneDispatcher() throws Exception {
        DispatcherPayrollService service = mock(DispatcherPayrollService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.getCurrentCompanyId()).thenReturn(10L);
        String nextMonth = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .plusMonths(1).withDayOfMonth(1).toString();

        MockMvcBuilders.standaloneSetup(new CompanyDispatcherPayrollController(service, currentUser)).build()
                .perform(put("/api/company/dispatcher-payroll/dispatchers/31/base-salary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"" + nextMonth + "\",\"baseSalaryVnd\":9000000}"))
                .andExpect(status().isOk());
    }

    @Test
    void canUpdateDraftOvertimeDaysAndKpi() throws Exception {
        DispatcherPayrollService service = mock(DispatcherPayrollService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.getCurrentCompanyId()).thenReturn(10L);

        MockMvcBuilders.standaloneSetup(new CompanyDispatcherPayrollController(service, currentUser)).build()
                .perform(put("/api/company/dispatcher-payroll/periods/2026-11/dispatchers/31")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overtimeDays\":3,\"kpiAmountVnd\":1500000}"))
                .andExpect(status().isOk());
    }
}
