package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollStatementResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.PayrollTripLineResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.payroll.DriverPayrollService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Phiếu lương của chính tài xế đang hỏi, đọc qua đúng hàm của {@code GET /api/driver/payroll}: kỳ chưa chốt thì chỉ
 * có lương cứng ({@code calculated: false}), hoa hồng, bốc dỡ và thưởng KPI hiện sau khi công ty chốt kỳ. Không trả
 * mã số nội bộ.
 */
@Component
@RequiredArgsConstructor
public class MyPayrollTool implements AssistantTool {

    /** Số dòng chuyến gửi cho mô hình; {@code tripCount} vẫn là số thật. */
    static final int MAX_TRIPS = 30;

    private final CurrentUserService currentUser;
    private final DriverPayrollService payroll;

    /** Phiếu lương một tháng; tiền là đồng, khối lượng là kg. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Statement(boolean calculated, String period, PayrollPeriodStatus periodStatus,
                            BigDecimal baseSalaryVnd, BigDecimal totalKg, BigDecimal rateVndPerKg,
                            BigDecimal kgAmountVnd, BigDecimal commissionPercent, BigDecimal handlingFeeVndPerKg,
                            BigDecimal handlingDriverPercent, BigDecimal handlingAmountVnd, BigDecimal kpiTargetKg,
                            Boolean kpiAchieved, BigDecimal kpiBonusVnd, BigDecimal totalSalaryVnd,
                            Integer tripCount, List<TripLine> trips) {
    }

    /** Phần của tài xế trong một chuyến của kỳ. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TripLine(String tripCode, BigDecimal planKg, BigDecimal sharePercent, BigDecimal allocatedKg,
                           BigDecimal kgAmountVnd, BigDecimal handlingAmountVnd, BigDecimal totalAmountVnd) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.DRIVER);
    }

    @Tool(name = "get_my_payroll", description = """
            Phiếu lương một tháng của chính tài xế đang hỏi: lương cứng, tổng kg đã chở, tiền theo kg, tiền bốc \
            dỡ, thưởng KPI (chỉ tiêu kg, đạt hay không, tiền thưởng — chỉ khi công ty dùng KPI), tổng lương và phần \
            của từng chuyến. Tổng lương đã gồm thưởng KPI. calculated = false nghĩa là công ty chưa chốt kỳ: chỉ có \
            lương cứng, hoa hồng, bốc dỡ và KPI hiện sau khi chốt — không tự tính. Dùng khi tài xế hỏi lương của mình.""")
    public Statement getMyPayroll(
            @ToolParam(description = "Kỳ lương dạng yyyy-MM, ví dụ 2026-11 (\"tháng này\" là tháng của hôm nay)")
            String period) {
        User me = currentUser.getCurrentUser();
        Long companyId = currentUser.getCurrentCompanyId();
        return statementOf(payroll.getDriverStatement(companyId, me.getId(), period == null ? null : period.trim()));
    }

    private static Statement statementOf(PayrollStatementResponse statement) {
        List<PayrollTripLineResponse> lines = statement.trips() == null ? List.of() : statement.trips();
        List<TripLine> trips = lines.stream().limit(MAX_TRIPS).map(MyPayrollTool::lineOf).toList();
        return new Statement(statement.calculated(), statement.period(), statement.status(),
                statement.baseSalaryVnd(), statement.totalKg(), statement.rateVndPerKg(), statement.kgAmountVnd(),
                statement.commissionPercent(), statement.handlingFeeVndPerKg(), statement.handlingDriverPercent(),
                statement.handlingAmountVnd(), statement.kpiTargetKg(), statement.kpiAchieved(),
                statement.kpiBonusVnd(), statement.totalSalaryVnd(),
                statement.calculated() ? lines.size() : null, statement.calculated() ? trips : null);
    }

    private static TripLine lineOf(PayrollTripLineResponse line) {
        return new TripLine(line.tripCode(), line.planKg(), line.sharePercent(), line.allocatedKg(),
                line.kgAmountVnd(), line.handlingAmountVnd(), line.totalAmountVnd());
    }
}
