package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleTypeRepository;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.vehicle.VehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Đội xe của công ty, như màn {@code /doi-xe}: cùng hàm của {@code GET /api/vehicles} ({@code VEHICLES_READ} — điều
 * phối viên và quản lý công ty), tự lọc theo công ty của người đang hỏi. Kích thước lòng thùng (cm) và tải trọng (kg)
 * lấy từ loại xe đang gắn, cũng chỉ trong công ty đó. Chỉ đọc; không trả mã số nội bộ.
 */
@Component
@RequiredArgsConstructor
public class FleetTool implements AssistantTool {

    private final CurrentUserService currentUser;
    private final VehicleService vehicles;
    private final VehicleTypeRepository vehicleTypes;

    /** Một xe; chuyến đang chạy chỉ khi xe đang phục vụ chuyến, ghi chú bảo dưỡng chỉ khi đang bảo dưỡng. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Vehicle(String name, String licensePlate, String vehicleType, VehicleStatus status,
                          String currentTripCode, TripStatus currentTripStatus, String maintenanceNote,
                          LocalDateTime maintenanceSince, BigDecimal maxPayloadKg, Integer innerLengthCm,
                          Integer innerWidthCm, Integer innerHeightCm) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.DISPATCHER, UserRole.MANAGER);
    }

    @Tool(name = "list_vehicles", description = """
            Xe của công ty như màn Đội xe, theo tên, tối đa 30 dòng kèm total là tổng số khớp bộ lọc. Mỗi dòng: tên \
            xe, biển số, loại xe, trạng thái (AVAILABLE sẵn sàng, IN_TRIP đang phục vụ chuyến — kèm mã và trạng thái \
            chuyến, MAINTENANCE bảo dưỡng — kèm ghi chú và từ lúc nào, RETIRED ngừng sử dụng), tải trọng tối đa (kg) \
            và kích thước lòng thùng dài × rộng × cao (cm) của loại xe. Bộ lọc sai giá trị trả lỗi INVALID_FILTER. \
            Dùng khi hỏi còn xe nào rảnh, xe nào đang chạy chuyến nào, xe nào chở được bao nhiêu.""")
    public Object listVehicles(
            @ToolParam(required = false, description = "Trạng thái: AVAILABLE, IN_TRIP, MAINTENANCE hoặc RETIRED; bỏ trống là mọi xe")
            String status) {
        VehicleStatus filter;
        try {
            filter = status == null || status.isBlank() ? null
                    : VehicleStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ToolResults.error(ToolResults.INVALID_FILTER);
        }
        PageResponse<VehicleResponse> page;
        try {
            page = vehicles.searchVehicles(0, ToolResults.LIST_ROWS, null, null, filter, "name", "ASC");
        } catch (AppException e) {
            return ToolResults.error(e);
        }
        Long companyId = currentUser.getCurrentCompanyId();
        Map<Long, Optional<VehicleType>> types = new HashMap<>();
        List<VehicleResponse> data = page.getData() == null ? List.of() : page.getData();
        List<Vehicle> rows = data.stream().map(vehicle -> {
            VehicleType type = vehicle.vehicleTypeId() == null ? null
                    : types.computeIfAbsent(vehicle.vehicleTypeId(),
                    id -> vehicleTypes.findByIdAndCompanyId(id, companyId)).orElse(null);
            boolean maintenance = vehicle.status() == VehicleStatus.MAINTENANCE;
            return new Vehicle(vehicle.name(), vehicle.licensePlate(), vehicle.vehicleTypeName(), vehicle.status(),
                    vehicle.currentTripCode(), vehicle.currentTripCode() == null ? null : vehicle.currentTripStatus(),
                    maintenance ? vehicle.maintenanceNote() : null, maintenance ? vehicle.maintenanceSince() : null,
                    type == null ? null : type.getMaxPayloadKg(), type == null ? null : type.getInnerLength(),
                    type == null ? null : type.getInnerWidth(), type == null ? null : type.getInnerHeight());
        }).toList();
        return ToolResults.list(rows, page.getTotalElements());
    }
}
