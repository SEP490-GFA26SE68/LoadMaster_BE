package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.warehouse.DamageOutcome;
import fu.se184491.loadmaster_be.constant.warehouse.ShortageStatus;
import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.warehouse.LoadingDamage;
import fu.se184491.loadmaster_be.entity.warehouse.StagingConfirmation;
import fu.se184491.loadmaster_be.entity.warehouse.StagingShortage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingDamageRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingShortageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseProgressTest {

    @Mock private StagingConfirmationRepository stagingConfirmationRepository;
    @Mock private StagingShortageRepository stagingShortageRepository;
    @Mock private LoadingDamageRepository loadingDamageRepository;
    @Mock private ManualUnloadConfirmationRepository manualConfirmationRepository;

    @InjectMocks
    private WarehouseProgress progress;

    private final CargoPackage first = CargoPackage.builder().id(1L).build();
    private final CargoPackage second = CargoPackage.builder().id(2L).build();
    private final List<PackagePlacement> placements = List.of(
            PackagePlacement.builder().id(71L).cargoPackage(first).build(),
            PackagePlacement.builder().id(72L).cargoPackage(second).build());

    private void staged(CargoPackage... packages) {
        when(stagingConfirmationRepository.findByTripId(10L)).thenReturn(java.util.Arrays.stream(packages)
                .map(pkg -> StagingConfirmation.builder().cargoPackage(pkg).method(StagingMethod.QR).build()).toList());
    }

    @Test
    void stagingIsCompleteOnceEveryPackageIsStaged() {
        staged(first, second);

        WarehouseProgress.State state = progress.of(10L, 50L);

        assertTrue(state.stagingComplete(placements));
        assertEquals(2, state.stagedCount(placements));
    }

    @Test
    void openShortageKeepsStagingIncompleteButKeepSearchingDoesNot() {
        staged(first);
        StagingShortage open = StagingShortage.builder().cargoPackage(second).status(ShortageStatus.OPEN).build();
        lenient().when(stagingShortageRepository.findByTripIdAndStatusOrderByReportedAtAsc(10L, ShortageStatus.OPEN))
                .thenReturn(List.of(open));

        WarehouseProgress.State state = progress.of(10L, 50L);

        assertFalse(state.stagingComplete(placements));
        assertEquals(1, state.openShortages());
    }

    @Test
    void leftOutDamageAndWarehouseManualConfirmationsAreRead() {
        staged();
        when(loadingDamageRepository.findByPlacementLoadPlanId(50L)).thenReturn(List.of(
                LoadingDamage.builder().placement(placements.get(0)).outcome(DamageOutcome.LEFT_OUT).build(),
                LoadingDamage.builder().placement(placements.get(1)).outcome(DamageOutcome.REPLANNED).build()));
        when(manualConfirmationRepository.findByTripIdOrderByRequestedAtAsc(10L)).thenReturn(List.of(
                ManualUnloadConfirmation.builder().cargoPackage(first).status(ManualConfirmStatus.PENDING)
                        .step(ManualConfirmStep.LOADING).build(),
                ManualUnloadConfirmation.builder().cargoPackage(second).status(ManualConfirmStatus.PENDING).build()));

        WarehouseProgress.State state = progress.of(10L, 50L);

        assertEquals(java.util.Set.of(71L), state.leftOut());
        assertEquals(1, state.pendingManual());
        assertTrue(state.manualByPackage().containsKey(1L));
    }

    @Test
    void sessionWithoutStagingStepIsNeverBlocked() {
        LoadingExecution execution = LoadingExecution.builder().loadPlan(LoadPlan.builder().id(50L).build()).build();

        assertDoesNotThrow(() -> progress.requireStagingComplete(execution, 10L, placements));
        verify(stagingConfirmationRepository, never()).findByTripId(any());
    }

    @Test
    void sessionWithStagingStepWaitsForStaging() {
        staged(first);
        LoadingExecution execution = LoadingExecution.builder().stagingRequired(true)
                .loadPlan(LoadPlan.builder().id(50L).build()).build();

        AppException error = assertThrows(AppException.class,
                () -> progress.requireStagingComplete(execution, 10L, placements));

        assertEquals(ErrorCode.STAGING_INCOMPLETE, error.getErrorCode());
    }
}
