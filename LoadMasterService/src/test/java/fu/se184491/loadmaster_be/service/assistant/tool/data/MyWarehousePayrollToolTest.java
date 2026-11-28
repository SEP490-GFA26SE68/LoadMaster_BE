package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollStatementResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollTripLineResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.payroll.WarehousePayrollService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phiếu lương kho của chính người hỏi: H2 cho kỳ chưa chốt và kỳ sai dạng; service giả cho kỳ đã chốt. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class MyWarehousePayrollToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EntityManager entityManager;
    @Autowired private MyWarehousePayrollTool tool;

    @MockitoBean private JwtDecoder jwtDecoder;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void openPeriodHasNoLoadedKgNorTotalAndNoName() {
        ToolFixtures data = new ToolFixtures(entityManager);
        Company company = data.company("AST-WP-LB", "Vận tải Long Bình");
        data.user(company, "ast-wp-worker", UserRole.WAREHOUSE_WORKER, "Đỗ Thị Mai");
        entityManager.flush();
        ToolFixtures.signIn("ast-wp-worker");

        String json = call(() -> tool.getMyWarehousePayroll(" 2026-11 "));
        JsonNode out = JSON.readTree(json);

        assertThat(out.get("calculated").asBoolean()).isFalse();
        assertThat(out.get("period").asString()).isEqualTo("2026-11");
        assertThat(out.has("loadedKg")).isFalse();
        assertThat(out.has("totalSalaryVnd")).isFalse();
        assertThat(out.has("trips")).isFalse();
        assertThat(out.has("tripCount")).isFalse();
        assertThat(json).doesNotContain("workerId").doesNotContain("Đỗ Thị Mai");
    }

    @Test
    void malformedPeriodIsAnError() {
        ToolFixtures data = new ToolFixtures(entityManager);
        Company company = data.company("AST-WP-LB2", "Vận tải Long Bình");
        data.user(company, "ast-wp-worker-2", UserRole.WAREHOUSE_WORKER, "Đỗ Thị Mai");
        entityManager.flush();
        ToolFixtures.signIn("ast-wp-worker-2");

        assertThat(call(() -> tool.getMyWarehousePayroll("11/2026"))).contains("\"error\"");
    }

    @Test
    void lockedPeriodReadsTheAskersOwnStatementAndCapsTheTrips() {
        CurrentUserService currentUser = mock(CurrentUserService.class);
        WarehousePayrollService payroll = mock(WarehousePayrollService.class);
        User me = User.builder().id(42L).fullName("Đỗ Thị Mai").build();
        when(currentUser.getCurrentUser()).thenReturn(me);
        when(currentUser.getCurrentCompanyId()).thenReturn(7L);
        List<WarehousePayrollTripLineResponse> lines = IntStream.rangeClosed(1, 35)
                .mapToObj(i -> new WarehousePayrollTripLineResponse(500L + i, "TRIP-W" + i, LocalDate.of(2026, 10, 1),
                        3, new BigDecimal("120")))
                .toList();
        when(payroll.getWorkerStatement(7L, 42L, "2026-10")).thenReturn(new WarehousePayrollStatementResponse(true,
                "2026-10", PayrollPeriodStatus.LOCKED, 42L, "Đỗ Thị Mai", new BigDecimal("7000000"), 105,
                new BigDecimal("4200"), new BigDecimal("200"), new BigDecimal("840000"), new BigDecimal("4000"), true,
                new BigDecimal("500000"), new BigDecimal("8340000"), lines));

        String json = ToolResults.json(new MyWarehousePayrollTool(currentUser, payroll).getMyWarehousePayroll("2026-10"));
        JsonNode out = JSON.readTree(json);

        verify(payroll).getWorkerStatement(7L, 42L, "2026-10");
        assertThat(out.get("totalSalaryVnd").decimalValue()).isEqualByComparingTo("8340000");
        assertThat(out.get("kpiAchieved").asBoolean()).isTrue();
        assertThat(out.get("tripCount").asInt()).isEqualTo(35);
        assertThat(out.get("trips")).hasSize(MyWarehousePayrollTool.MAX_TRIPS);
        assertThat(json).doesNotContain("workerId").doesNotContain("tripId").doesNotContain("Đỗ Thị Mai")
                .doesNotContain("\"501\"");
    }

    private static String call(Supplier<Object> invocation) {
        try {
            return ToolResults.json(invocation.get());
        } catch (AppException e) {
            return ToolResults.error(e);
        }
    }
}
