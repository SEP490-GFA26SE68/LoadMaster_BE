package fu.se184491.loadmaster_be.service.optimization.manual;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.optimization.ApproveEditsRequest;
import fu.se184491.loadmaster_be.dto.request.optimization.ApproveEditsRequest.EditedPlacement;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackagePackingProfile;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.dto.response.optimization.UnplacedPackageResponse;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackagePackingProfileRepository;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanWriteRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanWriteRepository.NewPlacement;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManualPlanApprovalServiceImplTest {

    @Mock LoadPlanQueryService loadPlanQueryService;
    @Mock LoadPlanRepository loadPlanRepository;
    @Mock LoadPlanWriteRepository writeRepository;
    @Mock TripRepository tripRepository;
    @Mock DeliveryStopRepository deliveryStopRepository;
    @Mock CargoPackageRepository cargoPackageRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock CurrentUserService currentUserService;
    @Mock CargoPackagePackingProfileRepository packingProfileRepository;

    ManualPlanApprovalServiceImpl service;

    private Trip trip;
    private LoadPlanDetailResponse source;
    private final LoadPlanDetailResponse saved = LoadPlanDetailResponse.builder().id(6L).build();

    @BeforeEach
    void setUp() {
        // Vùng dựng bằng bộ dựng thật trên hai kho giả: cùng một chỗ với lối đọc phương án.
        service = new ManualPlanApprovalServiceImpl(loadPlanQueryService, loadPlanRepository, writeRepository,
                tripRepository, cargoPackageRepository, auditLogRepository, currentUserService,
                new StopZoneResolver(deliveryStopRepository, cargoPackageRepository), packingProfileRepository);
        VehicleType type = VehicleType.builder().innerLength(600).innerWidth(240).innerHeight(240)
                .obstacles(new ArrayList<>()).build();
        trip = Trip.builder().id(10L).status(TripStatus.PLANNED)
                .vehicle(Vehicle.builder().id(3L).vehicleType(type).build()).build();
        source = LoadPlanDetailResponse.builder().id(5L).tripId(10L).approved(true).stale(false)
                .placements(new ArrayList<>(List.of(placement(101L, 1L, 0, 0), placement(102L, 2L, 100, 0))))
                .unplaced(new ArrayList<>())
                .build();
        when(loadPlanQueryService.getPlan(5L)).thenReturn(source);
        when(loadPlanQueryService.getPlan(6L)).thenReturn(saved);
        when(tripRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(trip));
        when(currentUserService.getCurrentUser()).thenReturn(User.builder().id(42L).build());
        when(loadPlanRepository.findById(5L)).thenReturn(Optional.of(LoadPlan.builder().id(5L).version(1)
                .job(OptimizationJob.builder().id(9L).trip(trip).build()).objective("MAX_VOLUME").algorithmName("GA").build()));
        when(loadPlanRepository.saveAndFlush(any(LoadPlan.class))).thenAnswer(call -> {
            LoadPlan plan = call.getArgument(0);
            plan.setId(6L);
            return plan;
        });
        PackageType rotatable = PackageType.builder().rotationAllowed(true)
                .allowRotateX(false).allowRotateY(false).allowRotateZ(true).build();
        when(cargoPackageRepository.findAllById(anyList())).thenReturn(List.of(
                CargoPackage.builder().id(1L).packageType(rotatable).build(),
                CargoPackage.builder().id(2L).build()));
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(10L))
                .thenReturn(List.of(DeliveryStop.builder().id(70L).trip(trip).stopSequence(1).build()));
    }

    /** Hai điểm giao cùng thể tích hàng trong thùng 600 cm: điểm 71 (giao sau) ở [0, 295], điểm 70 (giao trước) ở [305, 600]. */
    private void twoStops() {
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(10L)).thenReturn(List.of(
                DeliveryStop.builder().id(70L).trip(trip).stopSequence(1).build(),
                DeliveryStop.builder().id(71L).trip(trip).stopSequence(2).build()));
        source.getPlacements().get(0).setStopId(71L);
    }

    private LoadPlan savedPlan() {
        ArgumentCaptor<LoadPlan> plan = ArgumentCaptor.forClass(LoadPlan.class);
        verify(loadPlanRepository).saveAndFlush(plan.capture());
        return plan.getValue();
    }

    private static PlacementDetailResponse placement(long id, long packageId, double x, double y) {
        return PlacementDetailResponse.builder().id(id).packageId(packageId).weightKg(BigDecimal.valueOf(50))
                .posX(BigDecimal.valueOf(x)).posY(BigDecimal.valueOf(y)).posZ(BigDecimal.ZERO)
                .packedLength(BigDecimal.valueOf(100)).packedWidth(BigDecimal.valueOf(80))
                .packedHeight(BigDecimal.valueOf(60)).rotationType(0).stepSequence((int) id - 100)
                .pinned(false).stopId(70L).build();
    }

    private static ApproveEditsRequest move(long placementId, double x, double y, Integer rotation) {
        return ApproveEditsRequest.builder().placements(List.of(EditedPlacement.builder().placementId(placementId)
                .posX(BigDecimal.valueOf(x)).posY(BigDecimal.valueOf(y)).posZ(BigDecimal.ZERO)
                .rotationType(rotation).build())).build();
    }

    private static ErrorCode codeOf(Throwable error) {
        return ((AppException) error).getErrorCode();
    }

    @Test
    @SuppressWarnings("unchecked")
    void validMoveSavesANewApprovedManuallyEditedVersion() {
        LoadPlanDetailResponse result = service.approveEdits(5L, move(102L, 300, 100.5, null));

        assertThat(result).isSameAs(saved);
        verify(loadPlanRepository).unapproveAllByTripId(10L);
        ArgumentCaptor<LoadPlan> plan = ArgumentCaptor.forClass(LoadPlan.class);
        verify(loadPlanRepository).saveAndFlush(plan.capture());
        assertThat(plan.getValue().getApproved()).isTrue();
        assertThat(plan.getValue().getManuallyEdited()).isTrue();
        assertThat(plan.getValue().getParentPlanId()).isEqualTo(5L);
        assertThat(plan.getValue().getVersion()).isEqualTo(2);
        assertThat(plan.getValue().getApprovedBy().getId()).isEqualTo(42L);
        assertThat(plan.getValue().getAlgorithmName()).isEqualTo("GA");

        ArgumentCaptor<List<NewPlacement>> rows = ArgumentCaptor.forClass(List.class);
        verify(writeRepository).insertPlacements(eq(6L), rows.capture());
        assertThat(rows.getValue()).hasSize(2);
        NewPlacement moved = rows.getValue().get(1);
        assertThat(moved.posX()).isEqualByComparingTo("300");
        assertThat(moved.posY()).isEqualByComparingTo("100.5");
        assertThat(moved.stepSequence()).isEqualTo(2);
        verify(writeRepository).copyUnplaced(5L, 6L);
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertThat(audit.getValue().getActionType()).isEqualTo("PLAN_APPROVED");
        assertThat(audit.getValue().getNewValues()).containsEntry("movedCount", 1).containsEntry("manuallyEdited", true);
        // Người bấm "Duyệt bản chỉnh" là người làm: chuông ghi đúng tên và bỏ được việc chính mình làm
        assertThat(audit.getValue().getUser()).isNotNull();
        assertThat(audit.getValue().getUser().getId()).isEqualTo(42L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rotationSwapsThePackedSizeWhenThePackageTypeAllowsIt() {
        service.approveEdits(5L, move(101L, 0, 100, 1));

        ArgumentCaptor<List<NewPlacement>> rows = ArgumentCaptor.forClass(List.class);
        verify(writeRepository).insertPlacements(eq(6L), rows.capture());
        NewPlacement rotated = rows.getValue().get(0);
        assertThat(rotated.rotationType()).isEqualTo(1);
        assertThat(rotated.packedLength()).isEqualByComparingTo("80");
        assertThat(rotated.packedWidth()).isEqualByComparingTo("100");
    }

    @Test
    void rotationOfAPackageWithoutTypeIsNotAllowed() {
        assertThatThrownBy(() -> service.approveEdits(5L, move(102L, 300, 0, 1)))
                .extracting(ManualPlanApprovalServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.MANUAL_EDIT_ROTATION_NOT_ALLOWED);
        verify(loadPlanRepository, never()).saveAndFlush(any());
    }

    @Test
    void rotationOutsideTheAxesThePackageTypeAllowsIsRefused() {
        // Loại kiện chỉ cho xoay quanh trục đứng: LHW (mã 2) phải lật kiện nằm ngang.
        assertThatThrownBy(() -> service.approveEdits(5L, move(101L, 0, 100, 2)))
                .extracting(ManualPlanApprovalServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.MANUAL_EDIT_ROTATION_NOT_ALLOWED);
    }

    @Test
    void aPackageThatMustStayUprightIsNotLaidOnItsSide() {
        PackageType free = PackageType.builder().rotationAllowed(true)
                .allowRotateX(true).allowRotateY(true).allowRotateZ(true).build();
        when(cargoPackageRepository.findAllById(anyList())).thenReturn(List.of(
                CargoPackage.builder().id(1L).packageType(free).build(), CargoPackage.builder().id(2L).build()));
        when(packingProfileRepository.findByCargoPackageId(1L))
                .thenReturn(Optional.of(CargoPackagePackingProfile.builder().uprightOnly(true).build()));

        assertThatThrownBy(() -> service.approveEdits(5L, move(101L, 0, 100, 2)))
                .extracting(ManualPlanApprovalServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.MANUAL_EDIT_ROTATION_NOT_ALLOWED);
        service.approveEdits(5L, move(101L, 0, 100, 1));
        verify(loadPlanRepository).saveAndFlush(any());
    }

    @Test
    void rotationSwitchedOffOnThePackageTypeIsRefused() {
        PackageType locked = PackageType.builder().rotationAllowed(false)
                .allowRotateX(true).allowRotateY(true).allowRotateZ(true).build();
        when(cargoPackageRepository.findAllById(anyList())).thenReturn(List.of(
                CargoPackage.builder().id(1L).packageType(locked).build(), CargoPackage.builder().id(2L).build()));

        assertThatThrownBy(() -> service.approveEdits(5L, move(101L, 0, 100, 1)))
                .extracting(ManualPlanApprovalServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.MANUAL_EDIT_ROTATION_NOT_ALLOWED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rotationTakesThePackedSizeFromTheRealPackageDimensions() {
        // Kiện thật 100 × 80 × 60; phương án nguồn lưu kích thước xếp có sai số làm tròn.
        source.getPlacements().get(0).setPackedLength(BigDecimal.valueOf(100.01));
        PackageType free = PackageType.builder().rotationAllowed(true)
                .allowRotateX(true).allowRotateY(true).allowRotateZ(true).build();
        when(cargoPackageRepository.findAllById(anyList())).thenReturn(List.of(
                CargoPackage.builder().id(1L).packageType(free).actualLength(BigDecimal.valueOf(100))
                        .actualWidth(BigDecimal.valueOf(80)).actualHeight(BigDecimal.valueOf(60)).build(),
                CargoPackage.builder().id(2L).build()));

        service.approveEdits(5L, move(101L, 0, 100, 4));

        ArgumentCaptor<List<NewPlacement>> rows = ArgumentCaptor.forClass(List.class);
        verify(writeRepository).insertPlacements(eq(6L), rows.capture());
        NewPlacement rotated = rows.getValue().get(0);
        assertThat(rotated.rotationType()).isEqualTo(4);
        // HLW: cao dọc X, dài dọc Y, rộng dọc Z
        assertThat(rotated.packedLength()).isEqualByComparingTo("60");
        assertThat(rotated.packedWidth()).isEqualByComparingTo("100");
        assertThat(rotated.packedHeight()).isEqualByComparingTo("80");
    }

    @Test
    void rotatedPackageIsCheckedAgainstTheTruckBounds() {
        // WLH ở x = 530: dài theo X thành 80 cm, vượt 600 cm.
        assertThatThrownBy(() -> service.approveEdits(5L, move(101L, 530, 100, 1)))
                .extracting(ManualPlanApprovalServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.MANUAL_EDIT_OUT_OF_BOUNDS);
    }

    @Test
    void movingAPackageIntoItsOwnStopZoneRecountsRehandling() {
        twoStops();

        // 102 (điểm 70) đang có tâm x = 150 trong vùng của điểm 71; dời tới x = 400 là về đúng vùng của mình.
        service.approveEdits(5L, move(102L, 400, 0, null));

        assertThat(savedPlan().getRehandlingCount()).isZero();
    }

    @Test
    void movingAPackageOutOfItsZoneAddsRehandling() {
        twoStops();

        // 101 (điểm 71) sang x = 450: ngoài vùng của mình; 102 vẫn ở vùng của điểm 71.
        service.approveEdits(5L, move(101L, 450, 0, null));

        assertThat(savedPlan().getRehandlingCount()).isEqualTo(2);
    }

    @Test
    void rehandlingCountsTheVolumeOfUnplacedPackagesWhenSizingZones() {
        twoStops();
        // Một kiện điểm 70 không xếp được, 2 400 000 cm³: vùng điểm 71 còn [0; 84,3], vùng điểm 70 là [94,3; 600].
        source.setUnplaced(new ArrayList<>(List.of(UnplacedPackageResponse.builder().packageId(3L)
                .lengthCm(BigDecimal.valueOf(100)).widthCm(BigDecimal.valueOf(100)).heightCm(BigDecimal.valueOf(240)).build())));
        when(deliveryStopRepository.findRequirementStopsByPackageId(3L))
                .thenReturn(List.of(DeliveryStop.builder().id(70L).trip(trip).stopSequence(1).build()));

        // 102 ở x = 100 (tâm 150) nay nằm trong vùng của điểm 70; 101 ở x = 0 trong vùng điểm 71.
        service.approveEdits(5L, move(102L, 100, 100, null));

        assertThat(savedPlan().getRehandlingCount()).isZero();
    }

    @Test
    void rehandlingStaysUnknownWhenAnUnplacedPackageHasNoStopOnTheTrip() {
        twoStops();
        source.setUnplaced(new ArrayList<>(List.of(UnplacedPackageResponse.builder().packageId(3L)
                .lengthCm(BigDecimal.TEN).widthCm(BigDecimal.TEN).heightCm(BigDecimal.TEN).build())));
        when(deliveryStopRepository.findRequirementStopsByPackageId(3L)).thenReturn(List.of());

        service.approveEdits(5L, move(102L, 400, 0, null));

        assertThat(savedPlan().getRehandlingCount()).isNull();
    }

    @Test
    void pinOnlyChangeKeepsTheOptimizerRehandlingCount() {
        when(loadPlanRepository.findById(5L)).thenReturn(Optional.of(LoadPlan.builder().id(5L).version(1).rehandlingCount(3)
                .job(OptimizationJob.builder().id(9L).trip(trip).build()).objective("MAX_VOLUME").algorithmName("GA").build()));
        ApproveEditsRequest pinOnly = ApproveEditsRequest.builder().placements(List.of()).pinnedPlacementIds(List.of(101L)).build();

        service.approveEdits(5L, pinOnly);

        assertThat(savedPlan().getRehandlingCount()).isEqualTo(3);
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertThat(audit.getValue().getUser().getId()).isEqualTo(42L);
        assertThat(audit.getValue().getNewValues()).containsEntry("movedCount", 0).containsEntry("pinnedCount", 1);
    }

    @Test
    void editedPlanCarriesRecomputedCenterOfGravityAndAxleLoads() {
        trip.getVehicle().getVehicleType().setFrontAxleX(BigDecimal.valueOf(-100));
        trip.getVehicle().getVehicleType().setRearAxleX(BigDecimal.valueOf(400));
        trip.getVehicle().getVehicleType().setFrontAxleLimitKg(BigDecimal.valueOf(5000));
        trip.getVehicle().getVehicleType().setRearAxleLimitKg(BigDecimal.valueOf(5000));
        trip.getVehicle().getVehicleType().setEmptyFrontAxleLoadKg(BigDecimal.valueOf(1000));
        trip.getVehicle().getVehicleType().setEmptyRearAxleLoadKg(BigDecimal.valueOf(1000));

        service.approveEdits(5L, move(102L, 300, 100.5, null));

        // Tâm hai kiện 50 kg: x 50 và 350, y 40 và 140,5, z 30. Mômen quanh trục trước 50·150 + 50·450 = 30 000 trên đòn 500 cm.
        LoadPlan plan = savedPlan();
        assertThat(plan.getCogX()).isEqualByComparingTo("200");
        assertThat(plan.getCogY()).isEqualByComparingTo("90.25");
        assertThat(plan.getCogZ()).isEqualByComparingTo("30");
        assertThat(plan.getFrontAxleLoad()).isEqualByComparingTo("1040");
        assertThat(plan.getRearAxleLoad()).isEqualByComparingTo("1060");
    }

    @Test
    void overlappingMoveIsRejectedAndNothingIsSaved() {
        assertThatThrownBy(() -> service.approveEdits(5L, move(102L, 50, 0, null)))
                .extracting(ManualPlanApprovalServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.MANUAL_EDIT_OVERLAP);
        verify(loadPlanRepository, never()).saveAndFlush(any());
        verify(loadPlanRepository, never()).unapproveAllByTripId(anyLong());
    }

    @Test
    void unknownPlacementIsInvalid() {
        assertThatThrownBy(() -> service.approveEdits(5L, move(999L, 300, 0, null)))
                .extracting(ManualPlanApprovalServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.MANUAL_EDIT_INVALID);
    }

    @Test
    void editThatChangesNothingIsInvalid() {
        assertThatThrownBy(() -> service.approveEdits(5L, move(102L, 100, 0, null)))
                .extracting(ManualPlanApprovalServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.MANUAL_EDIT_INVALID);
    }

    @Test
    void stalePlanIsRefused() {
        source.setStale(true);

        assertThatThrownBy(() -> service.approveEdits(5L, move(102L, 300, 0, null)))
                .extracting(ManualPlanApprovalServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.PLAN_STALE);
    }

    @Test
    void tripPastPlanningIsRefused() {
        trip.setStatus(TripStatus.LOADING);

        assertThatThrownBy(() -> service.approveEdits(5L, move(102L, 300, 0, null)))
                .extracting(ManualPlanApprovalServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.PLAN_TRIP_NOT_PLANNED);
    }

    @Test
    void pinnedPlacementCannotMoveUnlessUnpinned() {
        source.getPlacements().get(1).setPinned(true);

        assertThatThrownBy(() -> service.approveEdits(5L, move(102L, 300, 0, null)))
                .extracting(ManualPlanApprovalServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.MANUAL_EDIT_INVALID);

        ApproveEditsRequest unpinAndMove = move(102L, 300, 0, null);
        unpinAndMove.setPinnedPlacementIds(List.of());
        service.approveEdits(5L, unpinAndMove);
        verify(loadPlanRepository).saveAndFlush(any());
    }
}
