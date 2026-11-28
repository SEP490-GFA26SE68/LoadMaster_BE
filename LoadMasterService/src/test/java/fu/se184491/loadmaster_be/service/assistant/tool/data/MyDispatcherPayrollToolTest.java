package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherPayrollStatementResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.payroll.DispatcherPayrollService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phiếu lương của chính điều phối viên: H2 cho kỳ chưa tính; service giả cho kỳ đã tính. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class MyDispatcherPayrollToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EntityManager entityManager;
    @Autowired private MyDispatcherPayrollTool tool;

    @MockitoBean private JwtDecoder jwtDecoder;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uncalculatedPeriodHasNoNumbersAndNoName() {
        ToolFixtures data = new ToolFixtures(entityManager);
        Company company = data.company("AST-DP-LB", "Vận tải Long Bình");
        data.user(company, "ast-dp-dispatcher", UserRole.DISPATCHER, "Lê Thu Trang");
        entityManager.flush();
        ToolFixtures.signIn("ast-dp-dispatcher");

        String json = call(() -> tool.getMyDispatcherPayroll("2026-11"));
        JsonNode out = JSON.readTree(json);

        assertThat(out.get("calculated").asBoolean()).isFalse();
        assertThat(out.has("totalSalaryVnd")).isFalse();
        assertThat(out.has("overtimeAmountVnd")).isFalse();
        assertThat(json).doesNotContain("dispatcherId").doesNotContain("Lê Thu Trang");
        assertThat(call(() -> tool.getMyDispatcherPayroll("tháng này"))).contains("\"error\"");
    }

    @Test
    void calculatedPeriodReadsTheAskersOwnStatement() {
        CurrentUserService currentUser = mock(CurrentUserService.class);
        DispatcherPayrollService payroll = mock(DispatcherPayrollService.class);
        when(currentUser.getCurrentUser()).thenReturn(User.builder().id(9L).fullName("Lê Thu Trang").build());
        when(currentUser.getCurrentCompanyId()).thenReturn(7L);
        when(payroll.getDispatcherStatement(7L, 9L, "2026-10")).thenReturn(new DispatcherPayrollStatementResponse(true,
                "2026-10", PayrollPeriodStatus.DRAFT, 9L, "Lê Thu Trang", new BigDecimal("12000000"),
                new BigDecimal("60000"), new BigDecimal("2"), 5, new BigDecimal("600000"), new BigDecimal("1000000"),
                new BigDecimal("13600000")));

        String json = ToolResults.json(new MyDispatcherPayrollTool(currentUser, payroll)
                .getMyDispatcherPayroll(" 2026-10 "));
        JsonNode out = JSON.readTree(json);

        verify(payroll).getDispatcherStatement(7L, 9L, "2026-10");
        assertThat(out.get("periodStatus").asString()).isEqualTo("DRAFT");
        assertThat(out.get("overtimeDays").asInt()).isEqualTo(5);
        assertThat(out.get("totalSalaryVnd").decimalValue()).isEqualByComparingTo("13600000");
        assertThat(json).doesNotContain("dispatcherId").doesNotContain("Lê Thu Trang");
    }

    private static String call(Supplier<Object> invocation) {
        try {
            return ToolResults.json(invocation.get());
        } catch (AppException e) {
            return ToolResults.error(e);
        }
    }
}
