package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollStatementResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollTripLineResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.payroll.CompanyPayrollRate;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.payroll.DriverPayrollService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Kỳ lương đọc qua đúng service của màn tài xế trên H2; phiếu đã chốt kiểm bằng service giả. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class MyPayrollToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EntityManager entityManager;
    @Autowired private MyPayrollTool tool;

    @MockitoBean private JwtDecoder jwtDecoder;

    @BeforeEach
    void seed() {
        Company company = Company.builder().companyCode("AST-PAY").companyName("Vận tải Long Bình")
                .taxCode("AST-PAY-TAX").billingEmail("ast-pay@loadmaster.test").status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now()).build();
        entityManager.persist(company);
        entityManager.persist(User.builder().keycloakId("ast-pay-driver").company(company)
                .email("ast-pay-driver@loadmaster.test").fullName("Trần Minh Khoa").userRoleType(UserRole.DRIVER)
                .status(UserStatus.ACTIVE).build());
        entityManager.persist(CompanyPayrollRate.builder().company(company).effectiveFrom(LocalDate.of(2026, 1, 1))
                .baseSalaryVnd(new BigDecimal("8000000")).rateVndPerKg(new BigDecimal("300"))
                .commissionPercent(new BigDecimal("10")).handlingFeeVndPerKg(new BigDecimal("50"))
                .handlingDriverPercent(new BigDecimal("40")).build());
        entityManager.flush();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("ast-pay-driver").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void periodWithSlashIsInvalid() {
        assertThat(call(() -> tool.getMyPayroll("2026/11"))).isEqualTo("{\"error\":\"PAYROLL_INVALID_PERIOD\"}");
        assertThat(call(() -> tool.getMyPayroll(null))).isEqualTo("{\"error\":\"PAYROLL_INVALID_PERIOD\"}");
    }

    @Test
    void openPeriodHasOnlyTheBaseSalary() {
        String json = call(() -> tool.getMyPayroll(" 2026-11 "));
        JsonNode out = JSON.readTree(json);

        assertThat(out.get("calculated").asBoolean()).isFalse();
        assertThat(out.get("period").asString()).isEqualTo("2026-11");
        assertThat(out.get("baseSalaryVnd").decimalValue()).isEqualByComparingTo("8000000");
        assertThat(out.has("totalSalaryVnd")).isFalse();
        assertThat(out.has("kgAmountVnd")).isFalse();
        assertThat(out.has("trips")).isFalse();
        assertThat(out.has("tripCount")).isFalse();
        assertThat(json).doesNotContain("driverId").doesNotContain("Trần Minh Khoa");
    }

    @Test
    void lockedPeriodListsTripsWithoutInternalIds() {
        CurrentUserService currentUser = mock(CurrentUserService.class);
        DriverPayrollService payroll = mock(DriverPayrollService.class);
        User driver = User.builder().id(42L).build();
        when(currentUser.getCurrentUser()).thenReturn(driver);
        when(currentUser.getCurrentCompanyId()).thenReturn(7L);
        List<PayrollTripLineResponse> lines = java.util.stream.IntStream.rangeClosed(1, 35)
                .mapToObj(i -> new PayrollTripLineResponse(1000L + i, "TRIP-2026-%04d".formatted(i),
                        new BigDecimal("1200"), new BigDecimal("100"), new BigDecimal("1200"),
                        new BigDecimal("36000"), new BigDecimal("24000"), new BigDecimal("60000")))
                .toList();
        when(payroll.getDriverStatement(7L, 42L, "2026-10")).thenReturn(new PayrollStatementResponse(true, "2026-10",
                PayrollPeriodStatus.LOCKED, 42L, "Trần Minh Khoa", new BigDecimal("8000000"), new BigDecimal("42000"),
                new BigDecimal("300"), new BigDecimal("1260000"), new BigDecimal("10"), new BigDecimal("50"),
                new BigDecimal("40"), new BigDecimal("840000"), new BigDecimal("10100000"), lines));

        JsonNode out = JSON.readTree(ToolResults.json(new MyPayrollTool(currentUser, payroll).getMyPayroll("2026-10")));

        assertThat(out.get("calculated").asBoolean()).isTrue();
        assertThat(out.get("periodStatus").asString()).isEqualTo("LOCKED");
        assertThat(out.get("totalSalaryVnd").decimalValue()).isEqualByComparingTo("10100000");
        assertThat(out.get("tripCount").asInt()).isEqualTo(35);
        assertThat(out.get("trips").size()).isEqualTo(MyPayrollTool.MAX_TRIPS);
        assertThat(out.get("trips").get(0).get("tripCode").asString()).isEqualTo("TRIP-2026-0001");
        assertThat(out.toString()).doesNotContain("tripId").doesNotContain("driverId").doesNotContain("driverName");
    }

    private static String call(Supplier<Object> invocation) {
        try {
            return ToolResults.json(invocation.get());
        } catch (AppException e) {
            return ToolResults.error(e);
        }
    }
}
