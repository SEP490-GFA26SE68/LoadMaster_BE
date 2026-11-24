package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.dto.response.payroll.DispatcherPayrollStatementResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.payroll.DispatcherPayrollService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * Phiếu lương của chính điều phối viên đang hỏi, đọc qua đúng hàm của {@code GET /api/dispatcher/payroll}: có số khi
 * quản lý đã tính kỳ (kể cả bảng nháp, {@code periodStatus} nói đã chốt hay chưa); chưa tính thì
 * {@code calculated: false} và không có số nào. Không trả mã số nội bộ hay họ tên.
 */
@Component
@RequiredArgsConstructor
public class MyDispatcherPayrollTool implements AssistantTool {

    private final CurrentUserService currentUser;
    private final DispatcherPayrollService payroll;

    /** Phiếu lương một tháng; tiền là đồng. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Statement(boolean calculated, String period, PayrollPeriodStatus periodStatus,
                            BigDecimal baseSalaryVnd, BigDecimal overtimeVndPerHour, BigDecimal overtimeHoursPerDay,
                            Integer overtimeDays, BigDecimal overtimeAmountVnd, BigDecimal kpiAmountVnd,
                            BigDecimal totalSalaryVnd) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.DISPATCHER);
    }

    @Tool(name = "get_my_dispatcher_payroll", description = """
            Phiếu lương một tháng của chính điều phối viên đang hỏi: lương cứng, tiền tăng ca (đơn giá mỗi giờ × số \
            giờ mỗi ngày × số ngày tăng ca), tiền KPI quản lý ghi cho kỳ và tổng lương = lương cứng + tăng ca + KPI. \
            periodStatus DRAFT là bảng nháp, quản lý còn sửa được; LOCKED là đã chốt. calculated = false nghĩa là \
            quản lý chưa tính kỳ này: chưa có số nào — không tự tính. Dùng khi điều phối viên hỏi lương của mình.""")
    public Statement getMyDispatcherPayroll(
            @ToolParam(description = "Kỳ lương dạng yyyy-MM, ví dụ 2026-11 (\"tháng này\" là tháng của hôm nay)")
            String period) {
        User me = currentUser.getCurrentUser();
        Long companyId = currentUser.getCurrentCompanyId();
        DispatcherPayrollStatementResponse statement =
                payroll.getDispatcherStatement(companyId, me.getId(), period == null ? null : period.trim());
        return new Statement(statement.calculated(), statement.period(), statement.status(),
                statement.baseSalaryVnd(), statement.overtimeVndPerHour(), statement.overtimeHoursPerDay(),
                statement.overtimeDays(), statement.overtimeAmountVnd(), statement.kpiAmountVnd(),
                statement.totalSalaryVnd());
    }
}
