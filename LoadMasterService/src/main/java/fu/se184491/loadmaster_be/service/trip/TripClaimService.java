package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.dto.request.trip.RejectTripClaimRequest;
import fu.se184491.loadmaster_be.dto.request.trip.TripClaimRequest;
import fu.se184491.loadmaster_be.dto.response.trip.OpenTripResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripClaimResponse;

import java.util.List;

public interface TripClaimService {
    List<OpenTripResponse> getOpenTrips();
    TripClaimResponse claimTrip(Long tripId, TripClaimRequest request);
    TripClaimResponse cancelClaim(Long tripId);
    List<TripClaimResponse> getTripClaims(Long tripId);
    TripClaimResponse approveClaim(Long tripId, Long claimId);
    TripClaimResponse rejectClaim(Long tripId, Long claimId, RejectTripClaimRequest request);
}
