package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.warehouse.ShortageStatus;
import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.warehouse.StagingConfirmation;
import fu.se184491.loadmaster_be.entity.warehouse.StagingShortage;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingShortageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StagingRecorderTest {

    @Mock private StagingConfirmationRepository stagingConfirmationRepository;
    @Mock private StagingShortageRepository stagingShortageRepository;
    @Mock private ManualUnloadConfirmationRepository manualConfirmationRepository;
    @Mock private WarehouseSessionLookup lookup;

    @InjectMocks
    private StagingRecorder recorder;

    private final Trip trip = Trip.builder().id(10L).build();
    private final CargoPackage pkg = CargoPackage.builder().id(1L).build();
    private final User worker = User.builder().id(9L).build();
    private final User dispatcher = User.builder().id(2L).build();

    @Test
    void recordingStagesThePackageUnderTheWorkerAndClosesItsShortage() {
        when(stagingConfirmationRepository.findByTripIdAndCargoPackageId(10L, 1L)).thenReturn(Optional.empty());
        when(stagingConfirmationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        StagingShortage open = StagingShortage.builder().id(5L).status(ShortageStatus.OPEN).build();
        when(stagingShortageRepository.findFirstByTripIdAndCargoPackageIdAndStatus(10L, 1L, ShortageStatus.OPEN))
                .thenReturn(Optional.of(open));

        recorder.record(trip, pkg, StagingMethod.MANUAL, worker, dispatcher);

        ArgumentCaptor<StagingConfirmation> saved = ArgumentCaptor.forClass(StagingConfirmation.class);
        verify(stagingConfirmationRepository).save(saved.capture());
        assertEquals(StagingMethod.MANUAL, saved.getValue().getMethod());
        assertEquals(worker, saved.getValue().getStagedBy());
        assertEquals(ShortageStatus.FOUND, open.getStatus());
        assertEquals(dispatcher, open.getResolvedBy());
        verify(lookup).audit(eq(trip), eq(dispatcher), eq("WAREHOUSE_STAGED"), any(), any(), any());
    }

    @Test
    void recordingAStagedPackageAgainChangesNothing() {
        when(stagingConfirmationRepository.findByTripIdAndCargoPackageId(10L, 1L))
                .thenReturn(Optional.of(StagingConfirmation.builder().build()));

        recorder.record(trip, pkg, StagingMethod.QR, worker, worker);

        verify(stagingConfirmationRepository, never()).save(any());
    }

    @Test
    void onlyThePendingStagingConfirmationOfThePackageIsDropped() {
        ManualUnloadConfirmation staging = ManualUnloadConfirmation.builder().id(70L).cargoPackage(pkg)
                .status(ManualConfirmStatus.PENDING).step(ManualConfirmStep.STAGING).build();
        ManualUnloadConfirmation loading = ManualUnloadConfirmation.builder().id(71L).cargoPackage(pkg)
                .status(ManualConfirmStatus.PENDING).step(ManualConfirmStep.LOADING).build();
        ManualUnloadConfirmation other = ManualUnloadConfirmation.builder().id(72L)
                .cargoPackage(CargoPackage.builder().id(2L).build())
                .status(ManualConfirmStatus.PENDING).step(ManualConfirmStep.STAGING).build();
        when(manualConfirmationRepository.findByTripIdAndStatusOrderByRequestedAtAsc(10L, ManualConfirmStatus.PENDING))
                .thenReturn(List.of(staging, loading, other));

        recorder.dropPendingManual(10L, 1L);

        verify(manualConfirmationRepository).delete(staging);
        verify(manualConfirmationRepository, never()).delete(loading);
        verify(manualConfirmationRepository, never()).delete(other);
    }
}
