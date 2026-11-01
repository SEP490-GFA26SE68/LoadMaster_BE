package fu.se184491.loadmaster_be.service.monitoring;

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
}
