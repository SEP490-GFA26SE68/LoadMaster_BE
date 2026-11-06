package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.cargo.PackageFlag;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.warehouse.ExecutionAbortReason;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import fu.se184491.loadmaster_be.service.trip.TripLifecycleService;
import fu.se184491.loadmaster_be.service.trip.TripStatusService;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoadingReplanServiceTest {

    @Mock private DeliveryRequirementRepository deliveryRequirementRepository;
    @Mock private CargoPackageRepository cargoPackageRepository;
    @Mock private LoadingExecutionRepository loadingExecutionRepository;
    @Mock private LoadPlanRepository loadPlanRepository;
    @Mock private ManualUnloadConfirmationRepository manualConfirmationRepository;
    @Mock private TripPackageResolver tripPackageResolver;
    @Mock private TripStatusService tripStatusService;
    @Mock private TripLifecycleService tripLifecycleService;

    @InjectMocks
    private LoadingReplanService service;

    private final Trip trip = Trip.builder().id(10L).status(TripStatus.LOADING).build();

    @Test
    void leavingOutRemovesThePackageFromTheTripRequirements() {
        CargoPackage dropped = CargoPackage.builder().id(1L).status(PackageStatus.PLANNED).build();
        CargoPackage kept = CargoPackage.builder().id(2L).status(PackageStatus.PLANNED).build();
        DeliveryRequirement requirement = DeliveryRequirement.builder().id(5L)
                .packages(new LinkedHashSet<>(Set.of(dropped, kept))).build();
        when(deliveryRequirementRepository.findByTripId(10L)).thenReturn(List.of(requirement));

        List<DeliveryRequirement> emptied = service.leaveOut(trip, dropped, PackageFlag.DAMAGED);

        assertEquals(Set.of(kept), requirement.getPackages());
        assertEquals(PackageStatus.PENDING, dropped.getStatus());
        assertEquals(PackageFlag.DAMAGED, dropped.getFlag());
        assertEquals(5L, dropped.getDroppedRequirementId());
        assertTrue(emptied.isEmpty());
        verify(deliveryRequirementRepository).save(requirement);
    }

    @Test
    void droppingTheLastPackageOfARequirementReleasesItFromTheTrip() {
        User actor = User.builder().id(2L).build();
        LoadingExecution execution = LoadingExecution.builder().id(60L).status(LoadingExecutionStatus.IN_PROGRESS).build();
        CargoPackage last = CargoPackage.builder().id(1L).status(PackageStatus.PLANNED).build();
        DeliveryRequirement requirement = DeliveryRequirement.builder().id(5L)
                .packages(new LinkedHashSet<>(Set.of(last))).build();
        when(deliveryRequirementRepository.findByTripId(10L)).thenReturn(List.of(requirement));

        service.returnToPlanning(trip, execution, last, PackageFlag.NOT_FOUND, actor);

        assertEquals(PackageFlag.NOT_FOUND, last.getFlag());
        assertEquals(ExecutionAbortReason.STAGING_SHORTAGE, execution.getAbortReason());
        InOrder order = inOrder(tripStatusService, tripLifecycleService);
        order.verify(tripStatusService).transitionTo(10L, TripStatus.PLANNED, 2L);
        order.verify(tripLifecycleService).releaseRequirement(trip, requirement, actor, "NO_PACKAGES_LEFT");
    }

    @Test
    void returningToPlanningAbortsTheSessionAndMakesPlansStale() {
        User actor = User.builder().id(2L).build();
        LoadingExecution execution = LoadingExecution.builder().id(60L).status(LoadingExecutionStatus.IN_PROGRESS).build();
        CargoPackage onTruck = CargoPackage.builder().id(3L).status(PackageStatus.LOADED).build();
        ManualUnloadConfirmation pendingStaging = ManualUnloadConfirmation.builder().id(70L)
                .status(ManualConfirmStatus.PENDING).step(ManualConfirmStep.STAGING).build();
        ManualUnloadConfirmation decided = ManualUnloadConfirmation.builder().id(71L)
                .status(ManualConfirmStatus.APPROVED).step(ManualConfirmStep.LOADING).build();
        when(manualConfirmationRepository.findByTripIdOrderByRequestedAtAsc(10L)).thenReturn(List.of(pendingStaging, decided));
        when(tripPackageResolver.packagesOfTrip(10L)).thenReturn(List.of(onTruck));

        service.returnToPlanning(trip, execution, null, PackageFlag.DAMAGED, actor);

        assertEquals(LoadingExecutionStatus.ABORTED, execution.getStatus());
        assertEquals(ExecutionAbortReason.DAMAGED_PACKAGE, execution.getAbortReason());
        verify(tripLifecycleService, never()).releaseRequirement(any(), any(), any(), any());
        assertEquals(PackageStatus.PLANNED, onTruck.getStatus());
        verify(manualConfirmationRepository).delete(pendingStaging);
        verify(manualConfirmationRepository, never()).delete(decided);
        verify(loadPlanRepository).markStaleByTripId(10L);
        verify(tripStatusService).transitionTo(10L, TripStatus.PLANNED, 2L);
    }
}
