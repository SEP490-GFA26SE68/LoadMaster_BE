package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStatusResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.TripLoadPlansResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.UnplacedPackageResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository.JobOwner;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository.PlacementRow;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository.PlanWithCompany;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository.UnplacedRow;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.optimization.Impl.LoadPlanQueryServiceImpl;
import fu.se184491.loadmaster_be.service.optimization.manual.StopZoneResolver;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoadPlanQueryServiceImplTest {

    private static final long COMPANY = 1L;
    private static final long OTHER_COMPANY = 2L;

    @Mock LoadPlanReadRepository readRepository;
    @Mock TripRepository tripRepository;
    @Mock CurrentUserService currentUserService;
    @Mock OptimizationClient optimizationClient;
    @Mock StopZoneResolver stopZoneResolver;

    @InjectMocks LoadPlanQueryServiceImpl service;

    @BeforeEach
    void signedIn() {
        when(currentUserService.getCurrentCompanyId()).thenReturn(COMPANY);
    }

    private static LoadPlanDetailResponse plan(long id, boolean approved) {
        return LoadPlanDetailResponse.builder().id(id).tripId(10L).approved(approved).build();
    }

    private static ErrorCode codeOf(Throwable error) {
        return ((AppException) error).getErrorCode();
    }

    @Test
    void planOfAnotherCompanyIsNotFound() {
        when(readRepository.findPlan(5L))
                .thenReturn(Optional.of(new PlanWithCompany(plan(5L, false), OTHER_COMPANY)));

        assertThatThrownBy(() -> service.getPlan(5L))
                .isInstanceOf(AppException.class)
                .extracting(LoadPlanQueryServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.LOAD_PLAN_NOT_FOUND);
    }

    @Test
    void jobOfAnotherCompanyIsNotFound() {
        when(readRepository.findJobOwner("job-1"))
                .thenReturn(Optional.of(new JobOwner(3L, "job-1", 10L, OTHER_COMPANY)));

        assertThatThrownBy(() -> service.getJobPlans("job-1"))
                .isInstanceOf(AppException.class)
                .extracting(LoadPlanQueryServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND);
    }

    @Test
    void tripOfAnotherCompanyIsNotFound() {
        when(tripRepository.findByIdAndCompanyId(10L, COMPANY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTripLoadPlans(10L))
                .isInstanceOf(AppException.class)
                .extracting(LoadPlanQueryServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.TRIP_NOT_FOUND);
    }

    @Test
    void ownPlanCarriesPlacementsAndUnplaced() {
        when(readRepository.findPlan(5L))
                .thenReturn(Optional.of(new PlanWithCompany(plan(5L, false), COMPANY)));
        PlacementDetailResponse placement = PlacementDetailResponse.builder()
                .id(500L).packageId(7L).posX(BigDecimal.ZERO).build();
        UnplacedPackageResponse unplaced = UnplacedPackageResponse.builder()
                .packageId(8L).reason("OUT_OF_SPACE").build();
        when(readRepository.findPlacements(any())).thenReturn(List.of(new PlacementRow(5L, placement)));
        when(readRepository.findUnplaced(any())).thenReturn(List.of(new UnplacedRow(5L, unplaced)));

        LoadPlanDetailResponse result = service.getPlan(5L);

        assertThat(result.getPlacements()).extracting(PlacementDetailResponse::getPackageId).containsExactly(7L);
        assertThat(result.getUnplaced()).extracting(UnplacedPackageResponse::getReason).containsExactly("OUT_OF_SPACE");
    }

    @Test
    void zonesAreAttachedWithTheInnerLengthOfTheTripLookedUpOncePerTrip() {
        when(tripRepository.findByIdAndCompanyId(10L, COMPANY)).thenReturn(Optional.of(new Trip()));
        LoadPlanDetailResponse first = plan(4L, true);
        LoadPlanDetailResponse second = plan(6L, false);
        when(readRepository.findPlansByTrip(10L)).thenReturn(List.of(first, second));
        Trip trip = new Trip();
        trip.setVehicle(Vehicle.builder().vehicleType(VehicleType.builder().innerLength(600).build()).build());
        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));

        service.getTripLoadPlans(10L);

        verify(stopZoneResolver).attach(first, 600);
        verify(stopZoneResolver).attach(second, 600);
        verify(tripRepository, times(1)).findById(10L);
    }

    @Test
    void tripPlansReportLatestApprovedPlan() {
        when(tripRepository.findByIdAndCompanyId(10L, COMPANY)).thenReturn(Optional.of(new Trip()));
        when(readRepository.findPlansByTrip(10L))
                .thenReturn(List.of(plan(4L, true), plan(6L, true), plan(7L, false)));

        TripLoadPlansResponse result = service.getTripLoadPlans(10L);

        assertThat(result.getApprovedPlanId()).isEqualTo(6L);
        assertThat(result.getPlans()).hasSize(3);
    }

    @Test
    void approveCallsOptimizationServiceAsCurrentUser() {
        when(readRepository.findPlan(5L))
                .thenReturn(Optional.of(new PlanWithCompany(plan(5L, false), COMPANY)))
                .thenReturn(Optional.of(new PlanWithCompany(plan(5L, true), COMPANY)));
        User dispatcher = new User();
        dispatcher.setId(42L);
        when(currentUserService.getCurrentUser()).thenReturn(dispatcher);
        when(tripRepository.findById(10L)).thenReturn(Optional.of(tripIn(TripStatus.PLANNED)));

        LoadPlanDetailResponse result = service.approvePlan(5L);

        verify(optimizationClient).approvePlan(5L, 42L);
        assertThat(result.getApproved()).isTrue();
    }

    @Test
    void approvingAnApprovedPlanIsRejectedWithoutCallingService() {
        when(readRepository.findPlan(5L))
                .thenReturn(Optional.of(new PlanWithCompany(plan(5L, true), COMPANY)));

        assertThatThrownBy(() -> service.approvePlan(5L))
                .isInstanceOf(AppException.class)
                .extracting(LoadPlanQueryServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.PLAN_ALREADY_APPROVED);
        verify(optimizationClient, never()).approvePlan(anyLong(), anyLong());
    }

    @Test
    void planOfATripPastPlanningCannotBeApproved() {
        when(readRepository.findPlan(5L))
                .thenReturn(Optional.of(new PlanWithCompany(plan(5L, false), COMPANY)));
        when(tripRepository.findById(10L)).thenReturn(Optional.of(tripIn(TripStatus.LOADING)));

        assertThatThrownBy(() -> service.approvePlan(5L))
                .isInstanceOf(AppException.class)
                .extracting(LoadPlanQueryServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.PLAN_TRIP_NOT_PLANNED);
        verify(optimizationClient, never()).approvePlan(anyLong(), anyLong());
    }

    private static Trip tripIn(TripStatus status) {
        Trip trip = new Trip();
        trip.setStatus(status);
        return trip;
    }

    @Test
    void jobStatusOfOwnJobIsReadFromTheDatabase() {
        when(readRepository.findJobOwner("job-1")).thenReturn(Optional.of(new JobOwner(7L, "job-1", 10L, COMPANY)));
        OptimizationJobStatusResponse row = OptimizationJobStatusResponse.builder()
                .id(7L).jobId("job-1").status("COMPLETED").packedPlanCount(3).build();
        when(readRepository.findJobStatus(7L)).thenReturn(Optional.of(row));

        assertThat(service.getJobStatus("job-1")).isSameAs(row);
    }

    @Test
    void jobStatusOfAnotherCompanyIsNotFound() {
        when(readRepository.findJobOwner("job-1"))
                .thenReturn(Optional.of(new JobOwner(7L, "job-1", 10L, OTHER_COMPANY)));

        assertThatThrownBy(() -> service.getJobStatus("job-1"))
                .isInstanceOf(AppException.class)
                .extracting(LoadPlanQueryServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND);
        verify(readRepository, never()).findJobStatus(anyLong());
    }

    @Test
    void stalePlanCannotBeApproved() {
        LoadPlanDetailResponse stale = plan(5L, false);
        stale.setStale(true);
        when(readRepository.findPlan(5L)).thenReturn(Optional.of(new PlanWithCompany(stale, COMPANY)));

        assertThatThrownBy(() -> service.approvePlan(5L))
                .isInstanceOf(AppException.class)
                .extracting(LoadPlanQueryServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.PLAN_STALE);
        verify(optimizationClient, never()).approvePlan(anyLong(), anyLong());
    }
}
