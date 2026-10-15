package fu.se184491.loadmaster_be.controller.subscription;

import fu.se184491.loadmaster_be.dto.response.subscription.PlatformReportSummaryResponse;
import fu.se184491.loadmaster_be.service.subscription.PlatformReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/reports")
@RequiredArgsConstructor
public class PlatformReportController {

    private final PlatformReportService platformReportService;

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAnyAuthority('SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<PlatformReportSummaryResponse> getSummaryReport() {
        return ResponseEntity.ok(platformReportService.getSummaryReport());
    }
}
