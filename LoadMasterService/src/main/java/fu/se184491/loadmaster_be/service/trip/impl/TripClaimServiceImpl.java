package fu.se184491.loadmaster_be.service.trip.impl;

import fu.se184491.loadmaster_be.constant.trip.TripClaimStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.trip.RejectTripClaimRequest;
import fu.se184491.loadmaster_be.dto.request.trip.TripClaimRequest;
import fu.se184491.loadmaster_be.dto.response.trip.OpenTripResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripClaimResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.trip.TripClaim;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripClaimRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.trip.TripClaimService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TripClaimServiceImpl implements TripClaimService {

    private final TripRepository tripRepository;
    private final TripClaimRepository tripClaimRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional(readOnly = true)
    public List<OpenTripResponse> getOpenTrips() {
        Long companyId = currentUserService.getCurrentCompanyId();
        User currentUser = currentUserService.getCurrentUser();

        List<Trip> companyTrips = tripRepository.findByCompanyId(companyId);
        List<Trip> openTrips = companyTrips.stream()
                .filter(t -> t.getDriver() == null && (t.getStatus() == TripStatus.DRAFT || t.getStatus() == TripStatus.PLANNED))
                .sorted(Comparator.comparing(Trip::getDepartureTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Trip::getId, Comparator.reverseOrder()))
                .toList();

        if (openTrips.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> openTripIds = openTrips.stream().map(Trip::getId).toList();

        Map<Long, Integer> stopCounts = new HashMap<>();
        List<Object[]> rawCounts = deliveryStopRepository.countStopsByTripIds(openTripIds);
        for (Object[] row : rawCounts) {
            Long tripId = (Long) row[0];
            Long count = (Long) row[1];
            stopCounts.put(tripId, count != null ? count.intValue() : 0);
        }

        List<TripClaim> myClaims = tripClaimRepository.findByDriverIdAndCompanyId(currentUser.getId(), companyId);
        Map<Long, TripClaim> myClaimsByTrip = myClaims.stream()
                .collect(Collectors.groupingBy(c -> c.getTrip().getId(),
                        Collectors.collectingAndThen(
                                Collectors.maxBy(Comparator.comparing(TripClaim::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                        .thenComparing(TripClaim::getId, Comparator.nullsLast(Comparator.naturalOrder()))),
                                Optional::get
                        )));

        return openTrips.stream().map(trip -> {
            String originWarehouse = trip.getOriginWarehouse() != null
                    ? trip.getOriginWarehouse().getWarehouseName()
                    : trip.getOriginName();
            int stopCount = stopCounts.getOrDefault(trip.getId(), 0);
            TripClaim claim = myClaimsByTrip.get(trip.getId());
            TripClaimStatus myClaimStatus = claim != null ? claim.getStatus() : null;
            String myRejectReason = (claim != null && claim.getStatus() == TripClaimStatus.REJECTED) ? claim.getRejectReason() : null;

            return new OpenTripResponse(
                    trip.getId(),
                    trip.getId(),
                    trip.getTripCode(),
                    trip.getTripName(),
                    trip.getDepartureTime(),
                    originWarehouse,
                    stopCount,
                    myClaimStatus,
                    myRejectReason
            );
        }).toList();
    }

    @Override
    @Transactional
    public TripClaimResponse claimTrip(Long tripId, TripClaimRequest request) {
        Long companyId = currentUserService.getCurrentCompanyId();
        User currentUser = currentUserService.getCurrentUser();

        Trip trip = tripRepository.findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));

        if (trip.getDriver() != null) {
            throw new AppException(ErrorCode.TRIP_CLAIM_TRIP_TAKEN);
        }

        if (trip.getStatus() != TripStatus.DRAFT && trip.getStatus() != TripStatus.PLANNED) {
            throw new AppException(ErrorCode.TRIP_CLAIM_TRIP_NOT_OPEN);
        }

        boolean hasPending = tripClaimRepository.existsByTripIdAndDriverIdAndStatus(tripId, currentUser.getId(), TripClaimStatus.PENDING);
        if (hasPending) {
            throw new AppException(ErrorCode.TRIP_CLAIM_DUPLICATE);
        }

        TripClaim claim = TripClaim.builder()
                .trip(trip)
                .driver(currentUser)
                .company(trip.getCompany())
                .status(TripClaimStatus.PENDING)
                .note(request != null ? request.note() : null)
                .build();

        TripClaim saved = tripClaimRepository.save(claim);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public TripClaimResponse cancelClaim(Long tripId) {
        Long companyId = currentUserService.getCurrentCompanyId();
        User currentUser = currentUserService.getCurrentUser();

        List<TripClaim> claims = tripClaimRepository.findByTripIdAndCompanyId(tripId, companyId);
        if (claims.isEmpty()) {
            throw new AppException(ErrorCode.TRIP_CLAIM_NOT_FOUND);
        }

        List<TripClaim> myClaims = claims.stream()
                .filter(c -> c.getDriver().getId().equals(currentUser.getId()))
                .toList();

        if (myClaims.isEmpty()) {
            throw new AppException(ErrorCode.TRIP_CLAIM_NOT_OWN);
        }

        TripClaim claim = myClaims.stream()
                .filter(c -> c.getStatus() == TripClaimStatus.PENDING)
                .findFirst()
                .orElse(myClaims.get(0));

        if (claim.getStatus() != TripClaimStatus.PENDING) {
            throw new AppException(ErrorCode.TRIP_CLAIM_ALREADY_DECIDED);
        }

        claim.setStatus(TripClaimStatus.CANCELLED);
        claim.setDecidedBy(currentUser);
        claim.setDecidedAt(LocalDateTime.now());
        TripClaim saved = tripClaimRepository.save(claim);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TripClaimResponse> getTripClaims(Long tripId) {
        Long companyId = currentUserService.getCurrentCompanyId();

        tripRepository.findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));

        return tripClaimRepository.findByTripIdAndCompanyIdOrderByCreatedAtDesc(tripId, companyId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public TripClaimResponse approveClaim(Long tripId, Long claimId) {
        Long companyId = currentUserService.getCurrentCompanyId();

        TripClaim claim = tripClaimRepository.findByIdAndCompanyId(claimId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_CLAIM_NOT_FOUND));

        if (!claim.getTrip().getId().equals(tripId)) {
            throw new AppException(ErrorCode.TRIP_CLAIM_NOT_FOUND);
        }

        if (claim.getStatus() != TripClaimStatus.PENDING) {
            throw new AppException(ErrorCode.TRIP_CLAIM_ALREADY_DECIDED);
        }

        Trip trip = claim.getTrip();
        if (trip.getStatus() != TripStatus.DRAFT && trip.getStatus() != TripStatus.PLANNED) {
            throw new AppException(ErrorCode.TRIP_CLAIM_TRIP_NOT_OPEN);
        }

        if (trip.getDriver() != null) {
            throw new AppException(ErrorCode.TRIP_CLAIM_TRIP_TAKEN);
        }

        User currentUser = currentUserService.getCurrentUser();

        // Gán tài xế vào chuyến
        trip.setDriver(claim.getDriver());
        tripRepository.save(trip);

        // Duyệt phiếu này
        claim.setStatus(TripClaimStatus.APPROVED);
        claim.setDecidedBy(currentUser);
        claim.setDecidedAt(LocalDateTime.now());
        TripClaim saved = tripClaimRepository.save(claim);

        // Tự động từ chối các phiếu PENDING còn lại của cùng chuyến
        List<TripClaim> otherPendingClaims = tripClaimRepository.findByTripIdAndCompanyIdAndStatus(tripId, companyId, TripClaimStatus.PENDING);
        for (TripClaim other : otherPendingClaims) {
            if (!other.getId().equals(claimId)) {
                other.setStatus(TripClaimStatus.REJECTED);
                other.setDecidedBy(currentUser);
                other.setDecidedAt(LocalDateTime.now());
                other.setRejectReason("Chuyến đã được giao cho tài xế khác");
                tripClaimRepository.save(other);
            }
        }

        return toResponse(saved);
    }

    @Override
    @Transactional
    public TripClaimResponse rejectClaim(Long tripId, Long claimId, RejectTripClaimRequest request) {
        Long companyId = currentUserService.getCurrentCompanyId();
        User currentUser = currentUserService.getCurrentUser();

        TripClaim claim = tripClaimRepository.findByIdAndCompanyId(claimId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_CLAIM_NOT_FOUND));

        if (!claim.getTrip().getId().equals(tripId)) {
            throw new AppException(ErrorCode.TRIP_CLAIM_NOT_FOUND);
        }

        if (claim.getStatus() != TripClaimStatus.PENDING) {
            throw new AppException(ErrorCode.TRIP_CLAIM_ALREADY_DECIDED);
        }

        claim.setStatus(TripClaimStatus.REJECTED);
        claim.setDecidedBy(currentUser);
        claim.setDecidedAt(LocalDateTime.now());
        claim.setRejectReason(request.reason() != null ? request.reason().trim() : null);

        TripClaim saved = tripClaimRepository.save(claim);
        return toResponse(saved);
    }

    private TripClaimResponse toResponse(TripClaim claim) {
        return TripClaimResponse.builder()
                .id(claim.getId())
                .tripId(claim.getTrip().getId())
                .driverUserId(claim.getDriver().getId())
                .driverName(claim.getDriver().getFullName())
                .driverPhone(claim.getDriver().getPhoneNumber())
                .status(claim.getStatus())
                .note(claim.getNote())
                .createdAt(claim.getCreatedAt())
                .decidedById(claim.getDecidedBy() != null ? claim.getDecidedBy().getId() : null)
                .decidedByName(claim.getDecidedBy() != null ? claim.getDecidedBy().getFullName() : null)
                .decidedAt(claim.getDecidedAt())
                .rejectReason(claim.getRejectReason())
                .build();
    }
}
