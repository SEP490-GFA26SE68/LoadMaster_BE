package fu.se184491.loadmaster_be.service.driver;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.driver.DeliveryIssueKind;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmReason;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.driver.ManualUnloadRequest;
import fu.se184491.loadmaster_be.dto.request.driver.RejectManualConfirmRequest;
import fu.se184491.loadmaster_be.dto.request.driver.ReportDeliveryIssueRequest;
import fu.se184491.loadmaster_be.dto.response.driver.DriverLoadPlanResponse;
import fu.se184491.loadmaster_be.dto.response.driver.DriverTripStopsResponse;
import fu.se184491.loadmaster_be.dto.response.driver.ManualConfirmationResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripLiveStopResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.driver.DeliveryIssue;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.driver.UnloadConfirmation;
import fu.se184491.loadmaster_be.entity.monitoring.GpsTracking;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.entity.warehouse.Deviation;
import fu.se184491.loadmaster_be.entity.warehouse.PlacementConfirmation;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.DuplicateUnloadException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.driver.DeliveryIssueRepository;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.driver.UnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.monitoring.GpsTrackingRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.warehouse.DeviationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.service.driver.Impl.DriverDeliveryServiceImpl;
import fu.se184491.loadmaster_be.service.monitoring.EtaCalculationService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.repository.warehouse.StagingConfirmationRepository;
import org.junit.jupiter.api.BeforeEach;
import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.service.warehouse.StagingRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriverDeliveryServiceImplTest {

    @Mock private DriverService driverService;
    @Mock private CurrentUserService currentUserService;
    @Mock private TripLookupHelper tripLookupHelper;
    @Mock private TripPackageResolver tripPackageResolver;
    @Mock private DeliveryStopRepository deliveryStopRepository;
    @Mock private LoadingExecutionRepository loadingExecutionRepository;
    @Mock private PackagePlacementRepository packagePlacementRepository;
    @Mock private PlacementConfirmationRepository placementConfirmationRepository;
    @Mock private DeviationRepository deviationRepository;
    @Mock private UnloadConfirmationRepository unloadConfirmationRepository;
    @Mock private CargoPackageRepository cargoPackageRepository;
    @Mock private DeliveryIssueRepository deliveryIssueRepository;
    @Mock private ManualUnloadConfirmationRepository manualConfirmationRepository;
    @Mock private StagingConfirmationRepository stagingConfirmationRepository;
    @Mock private StagingRecorder stagingRecorder;
    @Mock private GpsTrackingRepository gpsTrackingRepository;
    @Mock private EtaCalculationService etaCalculationService;
    @Mock private AuditLogRepository auditLogRepository;

    @InjectMocks
    private DriverDeliveryServiceImpl service;

    private Company company;
    private User driver;
    private Trip trip;
    private DeliveryStop stop1;
    private DeliveryStop stop2;

    @BeforeEach
    void setUp() {
        company = Company.builder().id(1L).build();
        driver = User.builder().id(8L).company(company).fullName("Tài xế A").build();
        VehicleType type = VehicleType.builder().id(3L).innerLength(600).innerWidth(240).innerHeight(240)
                .doorWidth(230).doorHeight(230).maxPayloadKg(new BigDecimal("5000")).build();
        Vehicle vehicle = Vehicle.builder().id(4L).licensePlate("51C-123.45").vehicleType(type).build();
        trip = Trip.builder().id(10L).tripCode("TRIP-10").company(company).driver(driver).vehicle(vehicle)
                .status(TripStatus.IN_TRANSIT).build();
        stop1 = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1).stopName("Biên Hoà")
                .status(DeliveryStopStatus.ARRIVED).actualArrival(LocalDateTime.of(2026, 11, 6, 9, 0)).build();
        stop2 = DeliveryStop.builder().id(31L).trip(trip).stopSequence(2).stopName("Dĩ An")
                .status(DeliveryStopStatus.PENDING).build();
        lenient().when(driverService.requireOwnTrip("10")).thenReturn(trip);
        lenient().when(currentUserService.getCurrentUser()).thenReturn(driver);
        lenient().when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        lenient().when(tripLookupHelper.requireTrip("10", 1L)).thenReturn(trip);
    }

    // ---- khung 3D ------------------------------------------------------------------------------

    @Test
    void loadPlanKeepsOnlyPackagesTheWarehouseLoadedWithUnloadOrder() {
        LoadPlan plan = LoadPlan.builder().id(70L).build();
        CargoPackage a = CargoPackage.builder().id(1L).packageCode("SHIP-1").actualWeightKg(new BigDecimal("12.5")).build();
        CargoPackage b = CargoPackage.builder().id(2L).packageCode("SHIP-2").status(PackageStatus.DELIVERED).build();
        CargoPackage c = CargoPackage.builder().id(3L).packageCode("SHIP-3").build();
        CargoPackage leftBehind = CargoPackage.builder().id(4L).packageCode("SHIP-4").build();
        PackagePlacement pa = PackagePlacement.builder().id(81L).loadPlan(plan).cargoPackage(a).stepSequence(1)
                .posX(0).posY(0).posZ(0).packedLength(100).packedWidth(80).packedHeight(60).build();
        PackagePlacement pb = PackagePlacement.builder().id(82L).loadPlan(plan).cargoPackage(b).stepSequence(2)
                .posX(100).posY(0).posZ(0).build();
        PackagePlacement pc = PackagePlacement.builder().id(83L).loadPlan(plan).cargoPackage(c).stepSequence(3)
                .posX(200).posY(0).posZ(0).build();
        PackagePlacement pd = PackagePlacement.builder().id(84L).loadPlan(plan).cargoPackage(leftBehind).stepSequence(4).build();
        when(loadingExecutionRepository.findAllByLoadPlanJobTripId(10L)).thenReturn(List.of(
                LoadingExecution.builder().id(5L).loadPlan(plan).status(LoadingExecutionStatus.COMPLETED).build()));
        when(placementConfirmationRepository.findByPlacementLoadPlanId(70L)).thenReturn(List.of(
                PlacementConfirmation.builder().id(91L).placement(pa).build(),
                PlacementConfirmation.builder().id(92L).placement(pb).build()));
        when(deviationRepository.findByPlacementLoadPlanId(70L)).thenReturn(List.of(
                Deviation.builder().id(95L).placement(pc).actualPosX(210).actualPosY(5).actualPosZ(0).build()));
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(10L)).thenReturn(List.of(stop1, stop2));
        when(tripPackageResolver.packagesOfStop(30L)).thenReturn(List.of(a, b));
        when(tripPackageResolver.packagesOfStop(31L)).thenReturn(List.of(c, leftBehind));
        when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(70L)).thenReturn(List.of(pa, pb, pc, pd));

        DriverLoadPlanResponse response = service.getLoadPlan("10");

        assertEquals(70L, response.getLoadPlanId());
        assertEquals(600, response.getInnerLength());
        assertEquals(230, response.getDoorWidth());
        assertEquals(3, response.getPlacements().size());
        DriverLoadPlanResponse.Placement first = response.getPlacements().get(0);
        assertEquals(new BigDecimal("12.5"), first.getWeightKg());
        assertEquals(30L, first.getStopId());
        // Điểm 1 dỡ trước, kiện xếp sau ra trước
        assertEquals(2, first.getUnloadOrder());
        assertEquals(1, response.getPlacements().get(1).getUnloadOrder());
        assertTrue(response.getPlacements().get(1).getUnloaded());
        DriverLoadPlanResponse.Placement moved = response.getPlacements().get(2);
        assertEquals(210, moved.getPosX());
        assertEquals(5, moved.getPosY());
        assertTrue(moved.getDeviated());
        assertEquals(3, moved.getUnloadOrder());
        assertEquals(2, moved.getStopSequence());
    }

    @Test
    void loadPlanNeedsALoadingSession() {
        trip.setStatus(TripStatus.PLANNED);

        AppException error = assertThrows(AppException.class, () -> service.getLoadPlan("10"));

        assertEquals(ErrorCode.NO_LOADED_PLAN, error.getErrorCode());
    }

    // ---- giờ đến ------------------------------------------------------------------------------

    @Test
    void stopsCarryDeadlineAndGpsEta() {
        LocalDateTime deadline = LocalDateTime.of(2026, 11, 6, 11, 0);
        stop2.setPlannedArrival(LocalDateTime.of(2026, 11, 6, 10, 0));
        stop2.setDeliveryRequirement(DeliveryRequirement.builder().id(20L).deadline(deadline).build());
        GpsTracking fix = GpsTracking.builder().id(1L).trip(trip).latitude(new BigDecimal("10.9"))
                .longitude(new BigDecimal("106.8")).recordedAt(LocalDateTime.of(2026, 11, 6, 9, 30)).build();
        stop1.setStatus(DeliveryStopStatus.COMPLETED);
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(10L)).thenReturn(List.of(stop1, stop2));
        when(gpsTrackingRepository.findFirstByTripIdOrderByRecordedAtDesc(10L)).thenReturn(Optional.of(fix));
        when(etaCalculationService.recalculateAndAlert(trip, fix)).thenReturn(List.of(
                TripLiveStopResponse.builder().stopId("31").eta("2026-11-06T10:45:00Z").build()));

        DriverTripStopsResponse response = service.getStops("10");

        assertEquals(30_000, response.getRefreshMs());
        assertEquals("GPS", response.getLocation().getSource());
        DriverTripStopsResponse.Stop second = response.getStops().get(1);
        assertEquals(deadline, second.getDeadline());
        assertEquals("OK", second.getPlannedStatus());
        assertEquals(LocalDateTime.of(2026, 11, 6, 10, 45), second.getEta());
        assertEquals("AT_RISK", second.getEtaStatus());
        assertNull(response.getStops().get(0).getEta());
    }

    @Test
    void stopsBeforeDepartureHaveNoEta() {
        trip.setStatus(TripStatus.LOADING);
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(10L)).thenReturn(List.of(stop2));

        DriverTripStopsResponse response = service.getStops("10");

        assertNull(response.getRefreshMs());
        assertNull(response.getLocation());
        assertNull(response.getStops().get(0).getEta());
        verify(etaCalculationService, never()).recalculateAndAlert(any(), any());
    }

    // ---- sự cố theo kiện -----------------------------------------------------------------------

    private CargoPackage packageAtStop1(PackageStatus status) {
        CargoPackage pkg = CargoPackage.builder().id(1L).trackingBarcode("PK-1").status(status).build();
        when(cargoPackageRepository.findById(1L)).thenReturn(Optional.of(pkg));
        when(tripPackageResolver.stopOfPackage(pkg)).thenReturn(Optional.of(stop1));
        when(deliveryStopRepository.findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(10L, DeliveryStopStatus.COMPLETED))
                .thenReturn(Optional.of(stop1));
        lenient().when(deliveryIssueRepository.save(any())).thenAnswer(invocation -> {
            DeliveryIssue saved = invocation.getArgument(0);
            saved.setId(50L);
            return saved;
        });
        lenient().when(manualConfirmationRepository.save(any())).thenAnswer(invocation -> {
            ManualUnloadConfirmation saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(60L);
            }
            return saved;
        });
        return pkg;
    }

    private static ReportDeliveryIssueRequest issue(DeliveryIssueKind kind, String note) {
        ReportDeliveryIssueRequest request = new ReportDeliveryIssueRequest();
        request.setKind(kind);
        request.setNote(note);
        return request;
    }

    @Test
    void refusedPackageStaysOnTheTruck() {
        CargoPackage pkg = packageAtStop1(PackageStatus.DELIVERED);
        UnloadConfirmation unload = UnloadConfirmation.builder().id(40L).cargoPackage(pkg).deliveryStop(stop1).build();
        ManualUnloadConfirmation pending = ManualUnloadConfirmation.builder().id(61L).cargoPackage(pkg)
                .status(ManualConfirmStatus.PENDING).build();
        when(unloadConfirmationRepository.existsByCargoPackageId(1L)).thenReturn(true);
        when(unloadConfirmationRepository.findByCargoPackageId(1L)).thenReturn(Optional.of(unload));
        when(manualConfirmationRepository.findByDeliveryStopIdOrderByRequestedAtAsc(30L)).thenReturn(List.of(pending));

        var response = service.reportIssue(1L, issue(DeliveryIssueKind.REFUSED, "Khách không nhận"));

        assertEquals("REFUSED", response.getKind());
        assertFalse(response.getUnloaded());
        assertEquals(PackageStatus.LOADED, pkg.getStatus());
        verify(unloadConfirmationRepository).delete(unload);
        verify(manualConfirmationRepository).delete(pending);
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals("DELIVERY_ISSUE_REPORTED", audit.getValue().getActionType());
        assertEquals(1L, audit.getValue().getCompanyId());
    }

    @Test
    void damagedPackageKeepsItsUnload() {
        CargoPackage pkg = packageAtStop1(PackageStatus.DELIVERED);
        when(unloadConfirmationRepository.existsByCargoPackageId(1L)).thenReturn(true);

        var response = service.reportIssue(1L, issue(DeliveryIssueKind.DAMAGED, null));

        assertTrue(response.getUnloaded());
        assertEquals(PackageStatus.DELIVERED, pkg.getStatus());
        verify(unloadConfirmationRepository, never()).delete(any());
    }

    @Test
    void otherIssueNeedsANote() {
        packageAtStop1(PackageStatus.LOADED);

        AppException error = assertThrows(AppException.class, () -> service.reportIssue(1L, issue(DeliveryIssueKind.OTHER, "  ")));

        assertEquals(ErrorCode.DELIVERY_ISSUE_NOTE_REQUIRED, error.getErrorCode());
        verify(deliveryIssueRepository, never()).save(any());
    }

    @Test
    void issueBeforeArrivingIsRejected() {
        stop1.setActualArrival(null);
        packageAtStop1(PackageStatus.LOADED);

        AppException error = assertThrows(AppException.class, () -> service.reportIssue(1L, issue(DeliveryIssueKind.DAMAGED, null)));

        assertEquals(ErrorCode.DELIVERY_STOP_NOT_ARRIVED, error.getErrorCode());
    }

    // ---- xác nhận tay ---------------------------------------------------------------------------

    private static ManualUnloadRequest manual(ManualConfirmReason reason, String note) {
        ManualUnloadRequest request = new ManualUnloadRequest();
        request.setReason(reason);
        request.setNote(note);
        return request;
    }

    @Test
    void manualUnloadRecordsUnloadAndWaitsForApproval() {
        CargoPackage pkg = packageAtStop1(PackageStatus.LOADED);
        when(unloadConfirmationRepository.existsByCargoPackageId(1L)).thenReturn(false);

        ManualConfirmationResponse response = service.requestManualUnload(1L, manual(ManualConfirmReason.QR_UNREADABLE, null));

        assertEquals("PENDING", response.getStatus());
        assertEquals("QR_UNREADABLE", response.getReason());
        assertEquals(PackageStatus.DELIVERED, pkg.getStatus());
        verify(unloadConfirmationRepository).save(any());
    }

    @Test
    void manualUnloadOfAnUnloadedPackageIsRejected() {
        packageAtStop1(PackageStatus.DELIVERED);

        assertThrows(DuplicateUnloadException.class, () -> service.requestManualUnload(1L, manual(ManualConfirmReason.LABEL_DAMAGED, null)));
    }

    @Test
    void manualUnloadWithOtherReasonNeedsANote() {
        packageAtStop1(PackageStatus.LOADED);

        AppException error = assertThrows(AppException.class,
                () -> service.requestManualUnload(1L, manual(ManualConfirmReason.OTHER, null)));

        assertEquals(ErrorCode.MANUAL_CONFIRM_NOTE_REQUIRED, error.getErrorCode());
    }

    private ManualUnloadConfirmation pendingConfirmation(CargoPackage pkg) {
        ManualUnloadConfirmation confirmation = ManualUnloadConfirmation.builder().id(60L).trip(trip).deliveryStop(stop1)
                .cargoPackage(pkg).reason(ManualConfirmReason.LABEL_DAMAGED).status(ManualConfirmStatus.PENDING)
                .requestedBy(driver).build();
        when(manualConfirmationRepository.findById(60L)).thenReturn(Optional.of(confirmation));
        return confirmation;
    }

    @Test
    void dispatcherApprovesAPendingConfirmation() {
        User dispatcher = User.builder().id(2L).fullName("Điều phối B").company(company).build();
        when(currentUserService.getCurrentUser()).thenReturn(dispatcher);
        ManualUnloadConfirmation confirmation = pendingConfirmation(CargoPackage.builder().id(1L).build());

        ManualConfirmationResponse response = service.approveManualConfirmation("10", 60L);

        assertEquals("APPROVED", response.getStatus());
        assertEquals("Điều phối B", response.getDecidedByName());
        assertEquals(ManualConfirmStatus.APPROVED, confirmation.getStatus());
        verify(stagingRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void approvingAStagingConfirmationStagesThePackageUnderTheWorker() {
        trip.setStatus(TripStatus.LOADING);
        User dispatcher = User.builder().id(2L).fullName("Điều phối B").company(company).build();
        when(currentUserService.getCurrentUser()).thenReturn(dispatcher);
        CargoPackage pkg = CargoPackage.builder().id(1L).build();
        ManualUnloadConfirmation confirmation = pendingConfirmation(pkg);
        confirmation.setStep(ManualConfirmStep.STAGING);

        service.approveManualConfirmation("10", 60L);

        verify(stagingRecorder).record(trip, pkg, StagingMethod.MANUAL, driver, dispatcher);
    }

    @Test
    void rejectingPutsThePackageBackToNotUnloaded() {
        CargoPackage pkg = CargoPackage.builder().id(1L).status(PackageStatus.DELIVERED).build();
        ManualUnloadConfirmation confirmation = pendingConfirmation(pkg);
        UnloadConfirmation unload = UnloadConfirmation.builder().id(40L).cargoPackage(pkg).build();
        when(unloadConfirmationRepository.findByCargoPackageId(1L)).thenReturn(Optional.of(unload));
        RejectManualConfirmRequest request = new RejectManualConfirmRequest();
        request.setReason("Quét lại nhãn");

        ManualConfirmationResponse response = service.rejectManualConfirmation("10", 60L, request);

        assertEquals("REJECTED", response.getStatus());
        assertEquals("Quét lại nhãn", response.getDecisionNote());
        assertEquals(PackageStatus.LOADED, pkg.getStatus());
        assertEquals(ManualConfirmStatus.REJECTED, confirmation.getStatus());
        verify(unloadConfirmationRepository).delete(unload);
    }

    @Test
    void rejectingAStagingConfirmationPutsThePackageBackToNotStaged() {
        trip.setStatus(TripStatus.LOADING);
        CargoPackage pkg = CargoPackage.builder().id(1L).status(PackageStatus.PLANNED).build();
        ManualUnloadConfirmation confirmation = pendingConfirmation(pkg);
        confirmation.setStep(ManualConfirmStep.STAGING);
        RejectManualConfirmRequest request = new RejectManualConfirmRequest();
        request.setReason("Không thấy kiện");

        ManualConfirmationResponse response = service.rejectManualConfirmation("10", 60L, request);

        assertEquals("STAGING", response.getStep());
        verify(stagingConfirmationRepository).deleteByTripIdAndCargoPackageId(10L, 1L);
        verify(unloadConfirmationRepository, never()).findByCargoPackageId(any());
        assertEquals(PackageStatus.PLANNED, pkg.getStatus());
    }

    @Test
    void rejectingALoadingConfirmationPutsThePlacementBackToNotLoaded() {
        trip.setStatus(TripStatus.LOADING);
        CargoPackage pkg = CargoPackage.builder().id(1L).status(PackageStatus.LOADED).build();
        PackagePlacement placement = PackagePlacement.builder().id(71L).cargoPackage(pkg).build();
        ManualUnloadConfirmation confirmation = pendingConfirmation(pkg);
        confirmation.setStep(ManualConfirmStep.LOADING);
        confirmation.setPlacement(placement);
        PlacementConfirmation loaded = PlacementConfirmation.builder().id(80L).placement(placement).build();
        when(placementConfirmationRepository.findByPlacementId(71L)).thenReturn(Optional.of(loaded));
        RejectManualConfirmRequest request = new RejectManualConfirmRequest();
        request.setReason("Xếp sai kiện");

        ManualConfirmationResponse response = service.rejectManualConfirmation("10", 60L, request);

        assertEquals("LOADING", response.getStep());
        assertEquals(71L, response.getPlacementId());
        verify(placementConfirmationRepository).delete(loaded);
        assertEquals(PackageStatus.PLANNED, pkg.getStatus());
    }

    @Test
    void warehouseConfirmationCannotBeDecidedOnceTheTripLeft() {
        ManualUnloadConfirmation confirmation = pendingConfirmation(CargoPackage.builder().id(1L).build());
        confirmation.setStep(ManualConfirmStep.LOADING);

        AppException error = assertThrows(AppException.class, () -> service.approveManualConfirmation("10", 60L));

        assertEquals(ErrorCode.MANUAL_CONFIRM_NOT_PENDING, error.getErrorCode());
    }

    @Test
    void pendingListOfALoadingTripHoldsOnlyWarehouseConfirmations() {
        trip.setStatus(TripStatus.LOADING);
        ManualUnloadConfirmation staging = ManualUnloadConfirmation.builder().id(61L).trip(trip).deliveryStop(stop1)
                .cargoPackage(CargoPackage.builder().id(1L).build()).status(ManualConfirmStatus.PENDING)
                .step(ManualConfirmStep.STAGING).build();
        ManualUnloadConfirmation unloading = ManualUnloadConfirmation.builder().id(62L).trip(trip).deliveryStop(stop1)
                .cargoPackage(CargoPackage.builder().id(2L).build()).status(ManualConfirmStatus.PENDING).build();
        when(manualConfirmationRepository.findByTripIdAndStatusOrderByRequestedAtAsc(10L, ManualConfirmStatus.PENDING))
                .thenReturn(List.of(staging, unloading));

        List<ManualConfirmationResponse> pending = service.listPendingManualConfirmations("10");

        assertEquals(List.of(61L), pending.stream().map(ManualConfirmationResponse::getId).toList());
        assertEquals("STAGING", pending.get(0).getStep());
    }

    @Test
    void decidingTwiceIsRejected() {
        ManualUnloadConfirmation confirmation = pendingConfirmation(CargoPackage.builder().id(1L).build());
        confirmation.setStatus(ManualConfirmStatus.APPROVED);

        AppException error = assertThrows(AppException.class, () -> service.approveManualConfirmation("10", 60L));

        assertEquals(ErrorCode.MANUAL_CONFIRM_NOT_PENDING, error.getErrorCode());
    }

    @Test
    void confirmationOfAnotherTripIsNotFound() {
        Trip other = Trip.builder().id(11L).company(company).status(TripStatus.IN_TRANSIT).build();
        when(manualConfirmationRepository.findById(60L)).thenReturn(Optional.of(
                ManualUnloadConfirmation.builder().id(60L).trip(other).status(ManualConfirmStatus.PENDING).build()));

        AppException error = assertThrows(AppException.class, () -> service.approveManualConfirmation("10", 60L));

        assertEquals(ErrorCode.MANUAL_CONFIRM_NOT_FOUND, error.getErrorCode());
    }

    @Test
    void pendingListIsEmptyOnceTheTripIsNotRunning() {
        trip.setStatus(TripStatus.DELIVERED);

        assertTrue(service.listPendingManualConfirmations("10").isEmpty());
        verify(manualConfirmationRepository, never()).findByTripIdAndStatusOrderByRequestedAtAsc(any(), any());
    }
}
