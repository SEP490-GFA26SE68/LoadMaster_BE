package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollStatementResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollTripLineResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.payroll.WarehousePayrollService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Phiếu lương kho của chính nhân viên kho đang hỏi, đọc qua đúng hàm của {@code GET /api/warehouse/payroll}: kỳ chưa
 * chốt chỉ có lương cứng ({@code calculated: false}); kg đã xếp, tiền theo kg và thưởng KPI hiện sau khi công ty chốt
 * kỳ. Không trả mã số nội bộ hay họ tên.
 */
@Component
@RequiredArgsConstructor
public class MyWarehousePayrollTool implements AssistantTool {

    /** Số dòng chuyến gửi cho mô hình; {@code tripCount} vẫn là số thật. */
    static final int MAX_TRIPS = 30;

    private final CurrentUserService currentUser;
    private final WarehousePayrollService payroll;

    /** Phiếu lương kho một tháng; tiền là đồng, khối lượng là kg. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Statement(boolean calculated, String period, PayrollPeriodStatus periodStatus,
                            BigDecimal baseSalaryVnd, Integer loadedPackages, BigDecimal loadedKg,
                            BigDecimal rateVndPerKg, BigDecimal loadedAmountVnd, BigDecimal kpiTargetKg,
                            Boolean kpiAchieved, BigDecimal kpiBonusVnd, BigDecimal totalSalaryVnd,
                            Integer tripCount, List<TripLine> trips) {
    }

    /** Kiện nhân viên đã xếp trên một chuyến của kỳ. */
    public record TripLine(String tripCode, LocalDate loadedDate, int loadedPackages, BigDecimal loadedKg) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.WAREHOUSE_WORKER);
    }

    @Tool(name = "get_my_warehouse_payroll", description = """
            Phiếu lương kho một tháng của chính nhân viên kho đang hỏi: lương cứng, số kiện và tổng kg đã xếp lên \
            xe, đơn giá theo kg, tiền theo kg, thưởng KPI (chỉ tiêu kg, đạt hay không, tiền thưởng — chỉ khi công ty \
            dùng KPI), tổng lương và số kiện, kg đã xếp của từng chuyến. Tổng lương = lương cứng + tiền theo kg + \
            thưởng KPI. calculated = false nghĩa là công ty chưa chốt kỳ: chỉ có lương cứng, phần theo kg và KPI hiện \
            sau khi chốt — không tự tính. Dùng khi nhân viên kho hỏi lương của mình.""")
    public Statement getMyWarehousePayroll(
            @ToolParam(description = "Kỳ lương dạng yyyy-MM, ví dụ 2026-11 (\"tháng này\" là tháng của hôm nay)")
            String period) {
        User me = currentUser.getCurrentUser();
        Long companyId = currentUser.getCurrentCompanyId();
        return statementOf(payroll.getWorkerStatement(companyId, me.getId(), period == null ? null : period.trim()));
    }

    private static Statement statementOf(WarehousePayrollStatementResponse statement) {
        List<WarehousePayrollTripLineResponse> lines = statement.trips() == null ? List.of() : statement.trips();
        List<TripLine> trips = lines.stream().limit(MAX_TRIPS)
                .map(line -> new TripLine(line.tripCode(), line.loadedDate(), line.loadedPackages(), line.loadedKg()))
                .toList();
        return new Statement(statement.calculated(), statement.period(), statement.status(),
                statement.baseSalaryVnd(), statement.loadedPackages(), statement.loadedKg(), statement.rateVndPerKg(),
                statement.loadedAmountVnd(), statement.kpiTargetKg(), statement.kpiAchieved(),
                statement.kpiBonusVnd(), statement.totalSalaryVnd(),
                statement.calculated() ? lines.size() : null, statement.calculated() ? trips : null);
    }
}
