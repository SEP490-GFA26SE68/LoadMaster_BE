package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import fu.se184491.loadmaster_be.dto.response.planning.DeliveryRequirementResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.planning.DeliveryRequirementService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Danh sách yêu cầu giao của công ty, như màn {@code /yeu-cau-giao}: cùng hàm của {@code GET /api/delivery-requirements}
 * ({@code DELIVERY_READ} — điều phối viên và quản lý công ty), lọc theo trạng thái, ưu tiên và khoảng hạn, hạn gần
 * nhất trước. Không trả mã số nội bộ, toạ độ hay mã kiện.
 */
@Component
@RequiredArgsConstructor
public class RequirementsListTool implements AssistantTool {

    /** Trạng thái lọc được: {@code PARTIAL} là giao thiếu (suy lúc đọc, như màn). */
    static final Set<String> STATUSES = Set.of("PENDING", "ASSIGNED", "IN_TRIP", "PARTIAL");

    private final CurrentUserService currentUser;
    private final DeliveryRequirementService requirements;

    /** Một yêu cầu giao; {@code status} PARTIAL khi đã vào chuyến mà kho bỏ bớt kiện. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Requirement(String customerName, String destination, LocalDateTime deadline,
                              DeliveryRequirementPriority priority, String status, Integer receivedPackages,
                              Integer expectedPackages, Integer droppedPackages, String tripCode) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.DISPATCHER, UserRole.MANAGER);
    }

    @Tool(name = "list_delivery_requirements", description = """
            Yêu cầu giao của công ty như màn Yêu cầu giao, hạn gần nhất trước, tối đa 30 dòng kèm total là tổng \
            số khớp bộ lọc. Mỗi dòng: khách hàng, điểm đến, hạn giao, ưu tiên (LOW thấp, NORMAL bình thường, HIGH \
            cao, URGENT khẩn), trạng thái (PENDING chờ xếp chuyến, ASSIGNED đã vào chuyến, IN_TRIP xe đang chở, \
            PARTIAL giao thiếu vì kho bỏ bớt kiện), số kiện đã nhận / dự kiến, số kiện bị bỏ và mã chuyến. Bộ lọc \
            sai giá trị trả lỗi INVALID_FILTER. Dùng khi hỏi còn bao nhiêu yêu cầu chờ, yêu cầu nào sắp tới hạn \
            hay yêu cầu khẩn.""")
    public Object listDeliveryRequirements(
            @ToolParam(required = false, description = "Trạng thái: PENDING, ASSIGNED, IN_TRIP hoặc PARTIAL; bỏ trống là mọi trạng thái")
            String status,
            @ToolParam(required = false, description = "Ưu tiên: LOW, NORMAL, HIGH hoặc URGENT; bỏ trống là mọi mức")
            String priority,
            @ToolParam(required = false, description = "Hạn từ ngày (yyyy-MM-dd, tính cả ngày đó)")
            String deadlineFrom,
            @ToolParam(required = false, description = "Hạn tới ngày (yyyy-MM-dd, tính cả ngày đó)")
            String deadlineTo) {
        String statusCode = code(status);
        String priorityCode = code(priority);
        LocalDateTime from;
        LocalDateTime to;
        try {
            from = day(deadlineFrom);
            LocalDateTime toDay = day(deadlineTo);
            to = toDay == null ? null : toDay.plusDays(1);
        } catch (DateTimeParseException e) {
            return ToolResults.error(ToolResults.INVALID_FILTER);
        }
        if ((statusCode != null && !STATUSES.contains(statusCode))
                || (priorityCode != null && !isPriority(priorityCode))
                || (from != null && to != null && !from.isBefore(to))) {
            return ToolResults.error(ToolResults.INVALID_FILTER);
        }
        Page<DeliveryRequirementResponse> page = requirements.search(currentUser.getCurrentCompanyId(), null,
                statusCode, priorityCode, from, to, true,
                PageRequest.of(0, ToolResults.LIST_ROWS, Sort.by("deadline")));
        List<Requirement> rows = page.getContent().stream().map(RequirementsListTool::rowOf).toList();
        return ToolResults.list(rows, page.getTotalElements());
    }

    private static Requirement rowOf(DeliveryRequirementResponse requirement) {
        boolean partial = Boolean.TRUE.equals(requirement.getShortDelivered());
        Integer dropped = requirement.getDroppedPackageCount() != null && requirement.getDroppedPackageCount() > 0
                ? requirement.getDroppedPackageCount() : null;
        return new Requirement(requirement.getCustomerName(), requirement.getDestination(), requirement.getDeadline(),
                requirement.getPriority(),
                partial ? "PARTIAL" : requirement.getStatus() == null ? null : requirement.getStatus().name(),
                requirement.getReceivedPackageCount(), requirement.getExpectedPackageCount(), dropped,
                requirement.getTripCode());
    }

    private static String code(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    private static boolean isPriority(String value) {
        for (DeliveryRequirementPriority priority : DeliveryRequirementPriority.values()) {
            if (priority.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static LocalDateTime day(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value.strip()).atStartOfDay();
    }
}
