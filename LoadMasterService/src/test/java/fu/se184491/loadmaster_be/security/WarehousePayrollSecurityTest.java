package fu.se184491.loadmaster_be.security;

import fu.se184491.loadmaster_be.controller.company.CompanyWarehousePayrollController;
import fu.se184491.loadmaster_be.controller.warehouse.WarehousePayrollController;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollPeriodResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollStatementResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.service.payroll.WarehousePayrollService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lương kho: chỉ quản lý công ty vào màn của công ty, chỉ nhân viên kho đọc phiếu của chính mình. */
@WebMvcTest({CompanyWarehousePayrollController.class, WarehousePayrollController.class})
@Import({TestJwtSupport.class, CurrentUserService.class})
class WarehousePayrollSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean WarehousePayrollService payroll;
    @MockitoBean UserRepository users;

    @BeforeEach
    void seed() {
        Company company = Company.builder().id(1L).build();
        when(users.findByKeycloakId("own")).thenReturn(Optional.of(
                User.builder().id(31L).company(company).userRoleType(UserRole.WAREHOUSE_WORKER).build()));
        when(payroll.calculatePeriod(anyLong(), anyString()))
                .thenReturn(new WarehousePayrollPeriodResponse("2026-10", PayrollPeriodStatus.DRAFT, List.of()));
        when(payroll.getWorkerStatement(anyLong(), anyLong(), anyString()))
                .thenReturn(new WarehousePayrollStatementResponse(false, "2026-10", PayrollPeriodStatus.DRAFT, 31L, "Kho Một",
                        new BigDecimal("5000000"), null, null, null, null, null, null, null, null, List.of()));
    }

    private static String bearer(String role) throws Exception {
        return "Bearer " + TestJwtSupport.userToken("own", role);
    }

    @Test
    void managerCanCalculateCompanyWarehousePayroll() throws Exception {
        mvc.perform(post("/api/company/warehouse-payroll/periods/2026-10/calculate").header("Authorization", bearer("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period").value("2026-10"));
        verify(payroll).calculatePeriod(1L, "2026-10");
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAREHOUSE_WORKER", "DRIVER", "DISPATCHER", "COMPANY_ADMIN", "SYSTEM_ADMIN"})
    void onlyManagerReachesCompanyWarehousePayroll(String role) throws Exception {
        mvc.perform(post("/api/company/warehouse-payroll/periods/2026-10/calculate").header("Authorization", bearer(role)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/company/warehouse-payroll/settings").header("Authorization", bearer(role)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/company/warehouse-payroll/workers/31/base-salary").header("Authorization", bearer(role))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"baseSalaryVnd\":6000000}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(payroll);
    }

    @Test
    void warehouseWorkerReadsOwnStatementWithBaseOnlyBeforeLock() throws Exception {
        mvc.perform(get("/api/warehouse/payroll").param("period", "2026-10").header("Authorization", bearer("WAREHOUSE_WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.calculated").value(false))
                .andExpect(jsonPath("$.data.baseSalaryVnd").value(5000000))
                .andExpect(jsonPath("$.data.loadedKg").doesNotExist())
                .andExpect(jsonPath("$.data.totalSalaryVnd").doesNotExist());
        verify(payroll).getWorkerStatement(1L, 31L, "2026-10");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "DRIVER", "DISPATCHER"})
    void otherRolesCannotReadWarehousePayslip(String role) throws Exception {
        mvc.perform(get("/api/warehouse/payroll").param("period", "2026-10").header("Authorization", bearer(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(payroll);
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/warehouse/payroll").param("period", "2026-10")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/company/warehouse-payroll/settings")).andExpect(status().isUnauthorized());
        verifyNoInteractions(payroll);
    }

    @Test
    void contractBodyWithOnlyBaseSalaryIsAccepted() throws Exception {
        mvc.perform(put("/api/company/warehouse-payroll/workers/31/base-salary").header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"baseSalaryVnd\":6000000}"))
                .andExpect(status().isOk());
        verify(payroll).setWorkerBaseSalary(any(), any(), any());
    }

    @Test
    void negativeBaseSalaryIsRejected() throws Exception {
        mvc.perform(put("/api/company/warehouse-payroll/workers/31/base-salary").header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"baseSalaryVnd\":-1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(payroll);
    }
}
