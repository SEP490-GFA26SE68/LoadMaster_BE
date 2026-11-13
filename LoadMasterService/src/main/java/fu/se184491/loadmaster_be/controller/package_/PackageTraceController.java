package fu.se184491.loadmaster_be.controller.package_;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.response.audit.AuditLogResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.cargo.PackageTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Lịch sử của một kiện và nhãn QR của mọi kiện một chuyến. */
@RestController
@RequiredArgsConstructor
public class PackageTraceController {

    private final PackageTraceService packageTraceService;
    private final CurrentUserService currentUserService;

    /** Ba vai trò đọc được kho kiện: nhân viên kho, điều phối viên, quản lý công ty. */
    @GetMapping("/api/packages/{id}/history")
    @PreAuthorize("hasAnyRole('WAREHOUSE_WORKER', 'DISPATCHER', 'MANAGER')")
    public ApiResponse<List<AuditLogResponse>> history(@PathVariable Long id) {
        return ApiResponse.success("Lấy lịch sử kiện hàng thành công",
                packageTraceService.history(currentUserService.getCurrentCompanyId(), id));
    }

    /** {@code tripId}: mã số hoặc mã chuyến. Cùng vai trò với nhãn chọn tay. */
    @GetMapping("/api/packages/export/labels/trip/{tripId}")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'WAREHOUSE_WORKER')")
    public ResponseEntity<byte[]> tripLabels(@PathVariable String tripId) {
        byte[] pdf = packageTraceService.tripLabelsPdf(currentUserService.getCurrentCompanyId(), tripId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"trip-labels.pdf\"")
                .body(pdf);
    }
}
