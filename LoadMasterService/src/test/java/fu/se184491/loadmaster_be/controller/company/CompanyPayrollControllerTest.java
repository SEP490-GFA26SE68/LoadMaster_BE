package fu.se184491.loadmaster_be.controller.company;

import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.payroll.DriverPayrollService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fu.se184491.loadmaster_be.dto.request.payroll.DriverPayrollPenaltyRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private static org.springframework.test.web.servlet.ResultActions putRate(
            String commissionPercent, String handlingFee, String handlingDriverPercent) throws Exception {
        DriverPayrollService service = mock(DriverPayrollService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.getCurrentCompanyId()).thenReturn(10L);
        String nextMonth = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .plusMonths(1).withDayOfMonth(1).toString();
        return MockMvcBuilders.standaloneSetup(new CompanyPayrollController(service, currentUser)).build()
                .perform(put("/api/company/payroll/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"" + nextMonth + "\",\"baseSalaryVnd\":8000000,\"rateVndPerKg\":100,"
                                + "\"commissionPercent\":" + commissionPercent + ",\"handlingFeeVndPerKg\":" + handlingFee
                                + ",\"handlingDriverPercent\":" + handlingDriverPercent + "}"));
    }

    @Test
    void rejectsNegativeCommissionPercent() throws Exception {
        putRate("-1", "150", "85").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsCommissionPercentAboveOneHundred() throws Exception {
        putRate("100.01", "150", "85").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsCommissionPercentWithMoreThanTwoDecimals() throws Exception {
        putRate("15.555", "150", "85").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsHandlingDriverPercentWithMoreThanTwoDecimals() throws Exception {
        putRate("15", "150", "85.001").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsHandlingFeeWithMoreThanTwoDecimals() throws Exception {
        putRate("15", "150.555", "85").andExpect(status().isBadRequest());
    }

    @Test
    void acceptsPercentAndFeeWithTwoDecimals() throws Exception {
        putRate("15.25", "150.50", "84.75").andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.ResultActions putRateWithKpi(String kpiTargetKg, String kpiBonusVnd) throws Exception {
        DriverPayrollService service = mock(DriverPayrollService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.getCurrentCompanyId()).thenReturn(10L);
        String nextMonth = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .plusMonths(1).withDayOfMonth(1).toString();
        return MockMvcBuilders.standaloneSetup(new CompanyPayrollController(service, currentUser)).build()
                .perform(put("/api/company/payroll/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"" + nextMonth + "\",\"baseSalaryVnd\":8000000,\"rateVndPerKg\":100,"
                                + "\"commissionPercent\":15,\"handlingFeeVndPerKg\":150,\"handlingDriverPercent\":85,"
                                + "\"kpiTargetKg\":" + kpiTargetKg + ",\"kpiBonusVnd\":" + kpiBonusVnd + "}"));
    }

    @Test
    void acceptsAKpiTargetWithItsBonus() throws Exception {
        putRateWithKpi("1500.50", "500000").andExpect(status().isOk());
    }

    @Test
    void acceptsAnEmptyKpiTarget() throws Exception {
        putRateWithKpi("null", "null").andExpect(status().isOk());
    }

    @Test
    void rejectsAKpiTargetOfZeroOrLess() throws Exception {
        putRateWithKpi("0", "500000").andExpect(status().isBadRequest());
        putRateWithKpi("-5", "500000").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAKpiTargetWithMoreThanTwoDecimals() throws Exception {
        putRateWithKpi("1500.505", "500000").andExpect(status().isBadRequest());
        putRateWithKpi("1500.5555", "500000").andExpect(status().isBadRequest());
    }

    @Test
    void acceptsTheSmallestKpiTargetWithTwoDecimals() throws Exception {
        putRateWithKpi("0.01", "500000").andExpect(status().isOk());
    }

    @Test
    void rejectsANegativeKpiBonus() throws Exception {
        putRateWithKpi("1500", "-1").andExpect(status().isBadRequest());
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
                        .content("{\"effectiveFrom\":\"" + nextMonth + "\",\"baseSalaryVnd\":8000000,\"rateVndPerKg\":100,\"commissionPercent\":15,\"handlingFeeVndPerKg\":150,\"handlingDriverPercent\":85}"))
                .andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.ResultActions putPenalty(
            DriverPayrollService service, String body) throws Exception {
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.getCurrentCompanyId()).thenReturn(10L);
        return MockMvcBuilders.standaloneSetup(new CompanyPayrollController(service, currentUser)).build()
                .perform(put("/api/company/payroll/periods/2026-11/drivers/21/penalty")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    @Test
    void managerSetsADriversPenaltyForThatMonthOfTheirOwnCompany() throws Exception {
        DriverPayrollService service = mock(DriverPayrollService.class);
        putPenalty(service, "{\"penaltyVnd\":50000,\"note\":\"Giao trễ\"}").andExpect(status().isOk());
        verify(service).setDriverPenalty(10L, "2026-11", 21L,
                new DriverPayrollPenaltyRequest(new BigDecimal("50000"), "Giao trễ"));
    }

    @Test
    void rejectsANegativePenalty() throws Exception {
        DriverPayrollService service = mock(DriverPayrollService.class);
        putPenalty(service, "{\"penaltyVnd\":-1,\"note\":\"x\"}").andExpect(status().isBadRequest());
        verify(service, never()).setDriverPenalty(eq(10L), eq("2026-11"), eq(21L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsAPenaltyWithFractionsOfADong() throws Exception {
        putPenalty(mock(DriverPayrollService.class), "{\"penaltyVnd\":100.5,\"note\":\"x\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAReasonLongerThanTwoHundredCharacters() throws Exception {
        putPenalty(mock(DriverPayrollService.class), "{\"penaltyVnd\":1000,\"note\":\"" + "a".repeat(201) + "\"}")
                .andExpect(status().isBadRequest());
    }
}
