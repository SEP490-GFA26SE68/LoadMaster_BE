package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.cargo.LabelMethod;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.warehouse.DamageOutcome;
import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.dto.response.trip.TripReportResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.driver.DeliveryIssue;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.driver.UnloadConfirmation;
import fu.se184491.loadmaster_be.entity.monitoring.TripException;
import fu.se184491.loadmaster_be.entity.monitoring.TripReroute;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequestPackage;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.warehouse.PlacementConfirmation;
import fu.se184491.loadmaster_be.entity.warehouse.StagingConfirmation;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.driver.DeliveryIssueRepository;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.driver.UnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.monitoring.TripExceptionRepository;
import fu.se184491.loadmaster_be.repository.monitoring.TripRerouteRepository;
import fu.se184491.loadmaster_be.repository.pickup.PickupRequestRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingDamageRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingConfirmationRepository;
import fu.se184491.loadmaster_be.service.monitoring.DeadlineStatusRule;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Báo cáo chuyến (GET /api/trips/{id}/report): chỉ chuyến đã giao, hoặc bị huỷ khi đang vận chuyển. Số nào dữ liệu không ghi thì
 * không có ở đây — không đoán.
 */
@Service
@RequiredArgsConstructor
public class TripReportService {

    private final CurrentUserService currentUserService;
    private final TripLookupHelper tripLookupHelper;
    private final DeliveryStopRepository deliveryStopRepository;
    private final TripPackageResolver tripPackageResolver;
    private final UnloadConfirmationRepository unloadConfirmationRepository;
    private final DeliveryIssueRepository deliveryIssueRepository;
    private final ManualUnloadConfirmationRepository manualConfirmationRepository;
    private final StagingConfirmationRepository stagingConfirmationRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final PlacementConfirmationRepository placementConfirmationRepository;
    private final LoadingDamageRepository loadingDamageRepository;
    private final TripExceptionRepository tripExceptionRepository;
    private final TripRerouteRepository tripRerouteRepository;
    private final PickupRequestRepository pickupRequestRepository;

    /** Bước nhận dọc đường ở {@code verificationMethods}; xác nhận tay không có bước này. */
    static final String PICKUP_STEP = "PICKUP";
    private static final int QR = 0;
    private static final int CODE = 1;
    private static final int MANUAL = 2;

    /** Báo cáo có được không: đã giao, hoặc bị huỷ khi đang vận chuyển. */
    public static boolean reportAvailable(Trip trip) {
        return trip.getStatus() == TripStatus.DELIVERED
                || (trip.getStatus() == TripStatus.CANCELLED && trip.getCancelledFromStatus() == TripStatus.IN_TRANSIT);
    }

    @Transactional(readOnly = true)
    public TripReportResponse getReport(String tripIdOrCode) {
        Long companyId = currentUserService.getCurrentCompanyId();
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, companyId);
        if (!reportAvailable(trip)) {
            throw new AppException(ErrorCode.TRIP_REPORT_NOT_AVAILABLE);
        }

        List<DeliveryStop> stops = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId());
        List<ManualUnloadConfirmation> manuals = manualConfirmationRepository.findByTripIdOrderByRequestedAtAsc(trip.getId());
        LoadingExecution execution = executedLoading(trip.getId());

        int total = 0;
        int delivered = 0;
        int returned = 0;
        Set<Long> withIssue = new HashSet<>();
        List<UnloadConfirmation> unloads = new ArrayList<>();
        List<TripReportResponse.Stop> stopRows = new ArrayList<>();
        boolean cancelledOnRoad = trip.getStatus() == TripStatus.CANCELLED;
        for (DeliveryStop stop : stops) {
            List<CargoPackage> packages = tripPackageResolver.packagesOfStop(stop.getId());
            List<UnloadConfirmation> stopUnloads = unloadConfirmationRepository.findByDeliveryStopId(stop.getId());
            List<DeliveryIssue> issues = deliveryIssueRepository.findByDeliveryStopIdOrderByReportedAtAsc(stop.getId());
            boolean settled = stop.getStatus() == DeliveryStopStatus.COMPLETED;
            // Chuyến bị huỷ giữa đường: kiện của điểm chưa hoàn tất là hoàn trả, "đã giao" chỉ tính điểm đã hoàn tất
            boolean allReturned = cancelledOnRoad && !settled;
            int stopReturned = allReturned ? packages.size() : count(packages, PackageStatus.RETURNED);
            total += packages.size();
            delivered += allReturned ? 0 : count(packages, PackageStatus.DELIVERED);
            returned += stopReturned;
            unloads.addAll(stopUnloads);
            issues.stream().map(issue -> issue.getCargoPackage() == null ? null : issue.getCargoPackage().getId())
                    .filter(Objects::nonNull).forEach(withIssue::add);

            LocalDateTime deadline = stop.getDeliveryRequirement() != null ? stop.getDeliveryRequirement().getDeadline() : null;
            var plannedStatus = DeadlineStatusRule.resolve(stop.getPlannedArrival(), deadline);
            stopRows.add(TripReportResponse.Stop.builder()
                    .stopId(stop.getId())
                    .sequence(stop.getStopSequence())
                    .stopType(stop.getStopType() != null ? stop.getStopType().name() : null)
                    .name(stop.getStopName())
                    .address(stop.getAddress())
                    .packageCount(packages.size())
                    .unloaded(stopUnloads.size())
                    .returned(stopReturned)
                    .issues(issues.size())
                    .plannedArrival(stop.getPlannedArrival())
                    .actualArrival(stop.getActualArrival())
                    .completedAt(stop.getCompletedAt())
                    .deadline(deadline)
                    .plannedStatus(plannedStatus != null ? plannedStatus.name() : null)
                    .arrivedOnTime(stop.getActualArrival() == null || deadline == null
                            ? null
                            : !stop.getActualArrival().isAfter(deadline))
                    .completed(settled)
                    .build());
        }
        List<PickupRequestPackage> picked = pickupRequestRepository.findByTripIdOrderByIdAsc(trip.getId()).stream()
                .flatMap(request -> request.getPackages().stream())
                .toList();

        List<StagingConfirmation> staged = stagingConfirmationRepository.findByTripId(trip.getId());
        List<PlacementConfirmation> placed = execution == null
                ? List.of()
                : placementConfirmationRepository.findByPlacementLoadPlanId(execution.getLoadPlan().getId());
        int damaged = execution == null ? 0 : (int) loadingDamageRepository.findByPlacementLoadPlanId(execution.getLoadPlan().getId())
                .stream().filter(damage -> damage.getOutcome() == DamageOutcome.LEFT_OUT).count();

        return TripReportResponse.builder()
                .tripId(trip.getId())
                .tripCode(trip.getTripCode())
                .tripName(trip.getTripName())
                .status(trip.getStatus())
                .vehicleName(trip.getVehicle() != null ? trip.getVehicle().getName() : null)
                .licensePlate(trip.getVehicle() != null ? trip.getVehicle().getLicensePlate() : null)
                .driverName(nameOf(trip.getDriver()))
                .departureTime(trip.getDepartureTime())
                .completedAt(trip.getCompletedAt())
                .deliveryMinutes(deliveryMinutes(trip.getDepartedAt(), trip.getCompletedAt()))
                .cancellation(trip.getStatus() == TripStatus.CANCELLED
                        ? new TripReportResponse.Cancellation(trip.getCancelReason(), trip.getCancelledFromStatus(),
                        trip.getCancelReturnedCount())
                        : null)
                .loading(execution == null ? null : TripReportResponse.Loading.builder()
                        .startedAt(execution.getStartedAt())
                        .completedAt(execution.getCompletedAt())
                        .sealNumber(execution.getSealNumber())
                        .sealedAt(execution.getSealedAt())
                        .build())
                .overrideReason(trip.getOverrideReason())
                .stops(stopRows)
                .packages(TripReportResponse.Packages.builder()
                        .total(total)
                        .staged(staged.size())
                        .loaded(placed.size())
                        .damaged(damaged)
                        .delivered(delivered)
                        .returned(returned)
                        .withIssue(withIssue.size())
                        .build())
                .verifications(verifications(staged, placed, unloads, manuals))
                .verificationMethods(verificationMethods(staged, placed, unloads, manuals, picked))
                .manualConfirmations(manuals.stream().map(TripReportService::manualRow).toList())
                .exceptions(tripExceptionRepository.findByTripIdOrderByReportedAtAsc(trip.getId()).stream()
                        .map(TripReportService::exceptionRow).toList())
                .reroutes(tripRerouteRepository.findByTripIdAndConfirmedAtIsNotNullOrderByConfirmedAtAsc(trip.getId()).stream()
                        .map(TripReportService::rerouteRow).toList())
                .build();
    }

    /** Phiên xếp đã hoàn tất mới nhất của chuyến (phiên bị huỷ giữa chừng không tính). */
    private LoadingExecution executedLoading(Long tripId) {
        return loadingExecutionRepository.findAllByLoadPlanJobTripId(tripId).stream()
                .filter(execution -> execution.getStatus() == LoadingExecutionStatus.COMPLETED)
                .max(Comparator.comparing(LoadingExecution::getCompletedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    /**
     * Đối chiếu có hiệu lực theo bước: soạn đọc cách ghi của từng kiện; xếp và dỡ tính là xác nhận tay khi kiện có xác nhận tay chưa bị
     * từ chối, còn lại là đối chiếu bằng nhãn.
     */
    static List<TripReportResponse.Verification> verifications(
            List<StagingConfirmation> staged,
            List<PlacementConfirmation> placed,
            List<UnloadConfirmation> unloads,
            List<ManualUnloadConfirmation> manuals
    ) {
        return verificationMethods(staged, placed, unloads, manuals, List.of()).stream()
                .filter(row -> !PICKUP_STEP.equals(row.step()))
                .map(row -> new TripReportResponse.Verification(row.step(), row.qr() + row.code(), row.manual()))
                .toList();
    }

    /**
     * Như {@link #verifications} nhưng tách đối chiếu bằng nhãn theo cách: quét QR hay gõ mã (bản ghi cũ không ghi cách là QR), và thêm
     * bước nhận dọc đường (máy chủ chưa có xác nhận tay ở điểm nhận nên {@code manual} luôn là 0).
     */
    static List<TripReportResponse.VerificationMethods> verificationMethods(
            List<StagingConfirmation> staged,
            List<PlacementConfirmation> placed,
            List<UnloadConfirmation> unloads,
            List<ManualUnloadConfirmation> manuals,
            List<PickupRequestPackage> picked
    ) {
        int[] staging = new int[3];
        for (StagingConfirmation item : staged) {
            staging[item.getMethod() == StagingMethod.MANUAL ? MANUAL : item.getMethod() == StagingMethod.CODE ? CODE : QR]++;
        }

        Set<Long> manualPlacements = new HashSet<>();
        Set<Long> manualUnloads = new HashSet<>();
        for (ManualUnloadConfirmation manual : manuals) {
            if (manual.getStatus() == ManualConfirmStatus.REJECTED) {
                continue;
            }
            ManualConfirmStep step = manual.getStep() == null ? ManualConfirmStep.UNLOADING : manual.getStep();
            if (step == ManualConfirmStep.LOADING && manual.getPlacement() != null) {
                manualPlacements.add(manual.getPlacement().getId());
            } else if (step == ManualConfirmStep.UNLOADING && manual.getCargoPackage() != null) {
                manualUnloads.add(manual.getCargoPackage().getId());
            }
        }
        int[] loading = new int[3];
        for (PlacementConfirmation item : placed) {
            boolean manual = item.getPlacement() != null && manualPlacements.contains(item.getPlacement().getId());
            loading[manual ? MANUAL : labelIndex(item.getMethod())]++;
        }
        int[] unloading = new int[3];
        for (UnloadConfirmation item : unloads) {
            boolean manual = item.getCargoPackage() != null && manualUnloads.contains(item.getCargoPackage().getId());
            unloading[manual ? MANUAL : labelIndex(item.getMethod())]++;
        }
        int[] pickup = new int[3];
        for (PickupRequestPackage line : picked) {
            if (line.getPickedAt() != null) {
                pickup[labelIndex(line.getPickMethod())]++;
            }
        }

        return List.of(
                methodsRow(ManualConfirmStep.STAGING.name(), staging),
                methodsRow(ManualConfirmStep.LOADING.name(), loading),
                methodsRow(ManualConfirmStep.UNLOADING.name(), unloading),
                methodsRow(PICKUP_STEP, pickup)
        );
    }

    private static int labelIndex(LabelMethod method) {
        return LabelMethod.orQr(method) == LabelMethod.CODE ? CODE : QR;
    }

    private static TripReportResponse.VerificationMethods methodsRow(String step, int[] counts) {
        return new TripReportResponse.VerificationMethods(step, counts[QR], counts[CODE], counts[MANUAL]);
    }

    /**
     * Phút từ lúc tài xế xuất phát tới khi hoàn tất điểm cuối; null khi thiếu một trong hai mốc (chuyến bị huỷ giữa đường, chuyến chạy
     * trước khi máy chủ ghi giờ xuất phát) hoặc hai mốc ngược nhau.
     */
    static Integer deliveryMinutes(LocalDateTime departedAt, LocalDateTime completedAt) {
        if (departedAt == null || completedAt == null || completedAt.isBefore(departedAt)) {
            return null;
        }
        return (int) Duration.between(departedAt, completedAt).toMinutes();
    }

    private static TripReportResponse.ManualConfirmation manualRow(ManualUnloadConfirmation manual) {
        return TripReportResponse.ManualConfirmation.builder()
                .id(manual.getId())
                .step((manual.getStep() == null ? ManualConfirmStep.UNLOADING : manual.getStep()).name())
                .packageCode(manual.getCargoPackage() != null ? manual.getCargoPackage().getPackageCode() : null)
                .stopSequence(manual.getDeliveryStop() != null ? manual.getDeliveryStop().getStopSequence() : null)
                .reason(manual.getReason() != null ? manual.getReason().name() : null)
                .note(manual.getNote())
                .status(manual.getStatus() != null ? manual.getStatus().name() : null)
                .requestedBy(nameOf(manual.getRequestedBy()))
                .requestedAt(manual.getRequestedAt())
                .decidedBy(nameOf(manual.getDecidedBy()))
                .decidedAt(manual.getDecidedAt())
                .decisionNote(manual.getDecisionNote())
                .build();
    }

    private static TripReportResponse.TripExceptionItem exceptionRow(TripException exception) {
        return TripReportResponse.TripExceptionItem.builder()
                .id(exception.getId())
                .type(exception.getType() != null ? exception.getType().name() : null)
                .description(exception.getDescription())
                .delayMinutes(exception.getDelayMinutes())
                .status(exception.getStatus() != null ? exception.getStatus().name() : null)
                .stopNumber(exception.getStopNumber())
                .reportedBy(nameOf(exception.getReportedBy()))
                .reportedAt(exception.getReportedAt())
                .escalatedAt(exception.getEscalatedAt())
                .resolvedBy(nameOf(exception.getResolvedBy()))
                .resolvedAt(exception.getResolvedAt())
                .resolutionNote(exception.getResolutionNote())
                .build();
    }

    private static TripReportResponse.Reroute rerouteRow(TripReroute reroute) {
        Map<String, Object> confirmed = reroute.getConfirmedJson() == null ? Map.of() : reroute.getConfirmedJson();
        return TripReportResponse.Reroute.builder()
                .stopNumber(reroute.getStopNumber())
                .route(confirmed.get("route") instanceof String route ? route : null)
                .distanceKm(confirmed.get("distanceKm") instanceof Number value ? value.doubleValue() : null)
                .durationMinutes(confirmed.get("durationMinutes") instanceof Number value ? value.intValue() : null)
                .extraMs(confirmed.get("extraMs") instanceof Number value ? value.longValue() : null)
                .confirmedAt(reroute.getConfirmedAt())
                .confirmedBy(nameOf(reroute.getConfirmedBy()))
                .build();
    }

    private static int count(List<CargoPackage> packages, PackageStatus status) {
        return (int) packages.stream().filter(item -> item.getStatus() == status).count();
    }

    private static String nameOf(User user) {
        return user == null ? null : user.getFullName();
    }
}
