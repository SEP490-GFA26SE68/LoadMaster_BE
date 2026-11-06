package fu.se184491.loadmaster_be.service.monitoring;

import fu.se184491.loadmaster_be.dto.request.monitoring.RenegotiateDeadlineRequest;
import fu.se184491.loadmaster_be.dto.request.monitoring.ReportExceptionRequest;
import fu.se184491.loadmaster_be.dto.request.monitoring.ResolveExceptionRequest;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripExceptionResponse;

import java.util.List;

public interface TripExceptionService {

    TripExceptionResponse report(String tripIdOrCode, Long companyId, ReportExceptionRequest request, Long reporterUserId);

    List<TripExceptionResponse> list(String tripIdOrCode, Long companyId);

    TripExceptionResponse resolve(
            String tripIdOrCode,
            Long exceptionId,
            Long companyId,
            ResolveExceptionRequest request,
            Long resolverUserId
    );

    TripExceptionResponse resolveByPathId(
            String tripIdOrCode,
            String exceptionIdOrCode,
            Long companyId,
            ResolveExceptionRequest request,
            Long resolverUserId
    );

    TripExceptionResponse escalate(
            String tripIdOrCode,
            String exceptionIdOrCode,
            Long companyId,
            Long escalatedByUserId,
            String reason
    );

    int escalateOverdue();

    /**
     * Quản lý gia hạn giao cho một yêu cầu giao của chuyến, trên sự cố đã chuyển lên quản lý
     * ({@code ESCALATED}). Sự cố giữ nguyên trạng thái tới khi điều phối viên đánh dấu đã xử lý.
     */
    TripExceptionResponse renegotiateDeadline(
            String tripIdOrCode,
            String exceptionIdOrCode,
            Long companyId,
            RenegotiateDeadlineRequest request,
            Long managerUserId
    );
}
