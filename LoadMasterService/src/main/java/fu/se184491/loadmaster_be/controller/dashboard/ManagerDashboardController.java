package fu.se184491.loadmaster_be.controller.dashboard;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.response.dashboard.ManagerDashboardResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.dashboard.ManagerDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@Tag(name = "Manager dashboard", description = "Số liệu bảng điều khiển của quản lý công ty")
public class ManagerDashboardController {

    private final ManagerDashboardService managerDashboardService;
    private final CurrentUserService currentUserService;

    /**
     * {@code from} / {@code to}: ngày theo giờ Việt Nam (YYYY-MM-DD), khoảng đóng.
     * {@code limit}: số chuyến của recentTrips, mặc định 10, tối đa 50.
     */
    @GetMapping("/api/manager/dashboard")
    @PreAuthorize("hasAnyRole('MANAGER', 'DISPATCHER')")
    @Operation(summary = "Số liệu bảng điều khiển của công ty trong kỳ")
    public ResponseEntity<ApiResponse<ManagerDashboardResponse>> getDashboard(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") int limit
    ) {
        ManagerDashboardResponse dashboard =
                managerDashboardService.get(currentUserService.getCurrentCompanyId(), from, to, limit);
        return ResponseEntity.ok(ApiResponse.success("Lấy số liệu bảng điều khiển thành công", dashboard));
    }
}
