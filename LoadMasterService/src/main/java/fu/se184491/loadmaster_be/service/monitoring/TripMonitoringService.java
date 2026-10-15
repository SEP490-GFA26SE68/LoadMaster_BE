package fu.se184491.loadmaster_be.service.monitoring;

import fu.se184491.loadmaster_be.dto.response.monitoring.MonitoringBoardResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripMonitoringResponse;

import java.util.List;

public interface TripMonitoringService {

    TripMonitoringResponse getMonitoring(String tripIdOrCode, Long companyId);

    List<TripMonitoringResponse> listFleetMonitoring(Long companyId);

    MonitoringBoardResponse getDashboard(Long companyId);
}
