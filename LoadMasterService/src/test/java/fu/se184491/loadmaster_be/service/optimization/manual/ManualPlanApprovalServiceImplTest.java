package fu.se184491.loadmaster_be.service.optimization.manual;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.optimization.ApproveEditsRequest;
import fu.se184491.loadmaster_be.dto.request.optimization.ApproveEditsRequest.EditedPlacement;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanWriteRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanWriteRepository.NewPlacement;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
    @Mock CargoPackageRepository cargoPackageRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock CurrentUserService currentUserService;

    @InjectMocks ManualPlanApprovalServiceImpl service;

    private Trip trip;
    private LoadPlanDetailResponse source;
    private final LoadPlanDetailResponse saved = LoadPlanDetailResponse.builder().id(6L).build();

    @BeforeEach
    void setUp() {
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
        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));
        when(currentUserService.getCurrentUser()).thenReturn(User.builder().id(42L).build());
        when(loadPlanRepository.findById(5L)).thenReturn(Optional.of(LoadPlan.builder().id(5L).version(1)
                .job(OptimizationJob.builder().id(9L).trip(trip).build()).objective("MAX_VOLUME").build()));
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
    void rotationOfAPackageWithoutTypeIsInvalid() {
        assertThatThrownBy(() -> service.approveEdits(5L, move(102L, 300, 0, 1)))
                .extracting(ManualPlanApprovalServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.MANUAL_EDIT_INVALID);
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
