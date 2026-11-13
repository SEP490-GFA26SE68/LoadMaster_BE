package fu.se184491.loadmaster_be.service.cargo;

import fu.se184491.loadmaster_be.dto.response.audit.AuditLogResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.service.audit.AuditLogQueryService;
import fu.se184491.loadmaster_be.service.export.PdfLabelExportService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Lịch sử của một kiện và nhãn của mọi kiện một chuyến. Lịch sử đọc từ nhật ký của công ty — bản ghi có đối tượng là chính kiện, hoặc
 * mang mã kiện — nên không cần bảng riêng: mọi nơi đổi kiện (soạn, báo thiếu, kiện hỏng, xác nhận tay, sự cố giao, gỡ cờ, tìm thấy lại)
 * đã ghi nhật ký.
 */
@Service
@RequiredArgsConstructor
public class PackageTraceService {

    /** Số mốc lịch sử nhiều nhất một lần đọc. */
    public static final int HISTORY_LIMIT = 100;

    private final CargoPackageRepository packageRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditLogQueryService auditLogQueryService;
    private final TripLookupHelper tripLookupHelper;
    private final TripPackageResolver tripPackageResolver;
    private final PdfLabelExportService pdfLabelExportService;

    /** Lịch sử của kiện, mới nhất trước. Kiện của công ty khác: {@code PACKAGE_NOT_FOUND} như kiện không có. */
    @Transactional(readOnly = true)
    public List<AuditLogResponse> history(Long companyId, Long packageId) {
        CargoPackage cargoPackage = packageRepository.findByIdAndCompany_Id(packageId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_NOT_FOUND));
        return auditLogQueryService.responsesOf(
                auditLogRepository.findPackageHistory(companyId, String.valueOf(cargoPackage.getId()), HISTORY_LIMIT));
    }

    /**
     * File PDF nhãn của mọi kiện chuyến đang giữ (theo yêu cầu giao, cùng khổ với nhãn chọn tay). Chuyến theo mã số hoặc mã chuyến, của
     * công ty khác là {@code TRIP_NOT_FOUND}; chuyến chưa có kiện nào là {@code TRIP_HAS_NO_PACKAGES}; quá 200 kiện là
     * {@code EXPORT_TOO_MANY_PACKAGES}.
     */
    @Transactional(readOnly = true)
    public byte[] tripLabelsPdf(Long companyId, String tripIdOrCode) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, companyId);
        List<Long> packageIds = tripPackageResolver.packagesOfTrip(trip.getId()).stream()
                .map(CargoPackage::getId)
                .toList();
        if (packageIds.isEmpty()) {
            throw new AppException(ErrorCode.TRIP_HAS_NO_PACKAGES);
        }
        return pdfLabelExportService.generateLabelsPdf(packageIds, companyId);
    }
}
