package fu.se184491.loadmaster_be.controller.audit;

import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.response.audit.AuditLogResponse;
import fu.se184491.loadmaster_be.service.audit.AuditLogFilter;
import fu.se184491.loadmaster_be.service.audit.AuditLogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@Tag(name = "Audit log", description = "Nhật ký hoạt động")
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    /**
     * Nhật ký mới nhất trước. {@code from} / {@code to}: ngày theo giờ Việt Nam (YYYY-MM-DD), khoảng đóng.
     * {@code companyId}: mã công ty, hoặc {@code platform} cho bản ghi không thuộc công ty nào
     * (chỉ quản trị hệ thống; quản trị công ty luôn đọc công ty mình).
     */
    @GetMapping("/api/audit-logs")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ADMIN') or hasAnyAuthority('AUDIT_READ', 'COMPANY_ADMIN')")
    @Operation(summary = "Nhật ký hoạt động theo phạm vi của người xem")
    public PageResponse<AuditLogResponse> getAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) String entityName,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String companyId,
            @RequestParam(required = false) String q
    ) {
        return auditLogQueryService.search(
                new AuditLogFilter(from, to, actionType, entityName, userId, q), companyId, page, size);
    }
}
