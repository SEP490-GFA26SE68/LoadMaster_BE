package fu.se184491.loadmaster_be.service.monitoring;

import fu.se184491.loadmaster_be.dto.response.monitoring.RerouteProposalResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripRerouteResponse;

public interface RerouteService extends RerouteQuery {

    RerouteProposalResponse propose(String tripIdOrCode, Long companyId);

    TripRerouteResponse confirm(String tripIdOrCode, int routeIndex, Long companyId, Long confirmerUserId);
}
