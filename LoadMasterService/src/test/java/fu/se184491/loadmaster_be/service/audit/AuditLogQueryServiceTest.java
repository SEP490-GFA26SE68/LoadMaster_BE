package fu.se184491.loadmaster_be.service.audit;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.response.audit.AuditLogResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.DeviationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.driver.UnloadConfirmationRepository;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.warehouse.PlacementConfirmation;
import fu.se184491.loadmaster_be.entity.warehouse.Deviation;
import fu.se184491.loadmaster_be.entity.driver.UnloadConfirmation;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditLogQueryServiceTest {

    @Mock AuditLogRepository auditLogRepository;
    @Mock CompanyRepository companyRepository;
    @Mock CurrentUserService currentUserService;
    @Mock TripRepository tripRepository;
    @Mock DeliveryStopRepository deliveryStopRepository;
    @Mock LoadPlanRepository loadPlanRepository;
    @Mock PackagePlacementRepository packagePlacementRepository;
    @Mock PlacementConfirmationRepository placementConfirmationRepository;
    @Mock DeviationRepository deviationRepository;
    @Mock LoadingExecutionRepository loadingExecutionRepository;
    @Mock UnloadConfirmationRepository unloadConfirmationRepository;

    @InjectMocks AuditLogQueryService service;

    private static final AuditLogFilter NO_FILTER = new AuditLogFilter(null, null, null, null, null, null);

    private static Company company(long id, String name) {
        return Company.builder().id(id).companyName(name).build();
    }

    private static User user(long id, UserRole role, Company company) {
        User user = User.builder().id(id).fullName("Người " + id).userRoleType(role).build();
        user.setCompany(company);
        return user;
    }

    @SuppressWarnings("unchecked")
    private void returns(AuditLog... logs) {
        when(auditLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(logs), inv.getArgument(1), logs.length));
    }

    @Test
    void mapsLogWithCompanyOfTheRecordAndActor() {
        Company longBinh = company(1, "Long Bình");
        when(currentUserService.getCurrentUser()).thenReturn(user(100, UserRole.SYSTEM_ADMIN, null));
        returns(AuditLog.builder()
                .id(5L).actionType("TRIP_STATUS_TRANSITION").entityName("Trip").entityId("9")
                .user(user(7, UserRole.DISPATCHER, longBinh)).companyId(1L)
                .oldValues(Map.of("status", "DRAFT")).newValues(Map.of("status", "PLANNED"))
                .ipAddress("10.0.0.1").createdAt(Instant.parse("2026-11-05T03:15:00Z"))
                .build());
        when(companyRepository.findAllById(anyIterable())).thenReturn(List.of(longBinh));

        PageResponse<AuditLogResponse> page = service.search(NO_FILTER, null, 0, 20);

        AuditLogResponse row = page.getData().get(0);
        assertThat(row.getId()).isEqualTo(5L);
        assertThat(row.getUserId()).isEqualTo(7L);
        assertThat(row.getUserName()).isEqualTo("Người 7");
        assertThat(row.getUserRole()).isEqualTo("DISPATCHER");
        assertThat(row.getCompanyId()).isEqualTo(1L);
        assertThat(row.getCompanyName()).isEqualTo("Long Bình");
        assertThat(row.getOldValues()).containsEntry("status", "DRAFT");
        assertThat(row.getNewValues()).containsEntry("status", "PLANNED");
        assertThat(row.getCreatedAt()).isEqualTo(Instant.parse("2026-11-05T03:15:00Z"));
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void entityCodeIsTripCodeOfTripStopPlanOrTripIdInValues() {
        when(currentUserService.getCurrentUser()).thenReturn(user(100, UserRole.SYSTEM_ADMIN, null));
        Trip trip = Trip.builder().id(9L).tripCode("TRIP-2026-0009").build();
        Trip other = Trip.builder().id(12L).tripCode("TRIP-2026-0012").build();
        DeliveryStop stop = DeliveryStop.builder().id(40L).trip(trip).build();
        LoadPlan plan = LoadPlan.builder().id(70L)
                .job(OptimizationJob.builder().id(3L).trip(other).build()).build();
        returns(
                AuditLog.builder().id(1L).entityName("Trip").entityId("9").build(),
                AuditLog.builder().id(2L).entityName("DeliveryStop").entityId("40").build(),
                AuditLog.builder().id(3L).entityName("LoadPlan").entityId("70").build(),
                AuditLog.builder().id(4L).entityName("LoadingExecution").entityId("5")
                        .newValues(Map.of("tripId", 12L)).build(),
                AuditLog.builder().id(5L).entityName("User").entityId("8").build());
        when(deliveryStopRepository.findAllById(anyIterable())).thenReturn(List.of(stop));
        when(loadPlanRepository.findAllById(anyIterable())).thenReturn(List.of(plan));
        when(tripRepository.findAllById(anyIterable())).thenReturn(List.of(trip, other));

        List<AuditLogResponse> rows = service.search(NO_FILTER, null, 0, 20).getData();

        assertThat(rows).extracting(AuditLogResponse::getEntityCode).containsExactly(
                "TRIP-2026-0009", "TRIP-2026-0009", "TRIP-2026-0012", "TRIP-2026-0012", null);
    }

    @Test
    void entityCodeOfWarehouseAndDriverLogsFollowsPlacementPlanExecutionAndStop() {
        when(currentUserService.getCurrentUser()).thenReturn(user(100, UserRole.SYSTEM_ADMIN, null));
        Trip trip = Trip.builder().id(9L).tripCode("TRIP-2026-0009").build();
        LoadPlan plan = LoadPlan.builder().id(70L)
                .job(OptimizationJob.builder().id(3L).trip(trip).build()).build();
        PackagePlacement placement = PackagePlacement.builder().id(500L).loadPlan(plan).build();
        DeliveryStop stop = DeliveryStop.builder().id(40L).trip(trip).build();
        returns(
                // WAREHOUSE_CONFIRM: giá trị mới có placementId
                AuditLog.builder().id(1L).actionType("WAREHOUSE_CONFIRM").entityName("PlacementConfirmation")
                        .entityId("11").newValues(Map.of("placementId", 500L, "confirmed", true)).build(),
                // DEVIATION_RECORDED cũ không có placementId: lần qua bản ghi sai lệch
                AuditLog.builder().id(2L).actionType("DEVIATION_RECORDED").entityName("Deviation")
                        .entityId("12").build(),
                // phiên xếp không ghi tripId: lần qua phương án
                AuditLog.builder().id(3L).actionType("LOADING_COMPLETED").entityName("LoadingExecution")
                        .entityId("13").build(),
                // DRIVER_UNLOAD: stopId trong giá trị mới
                AuditLog.builder().id(4L).actionType("DRIVER_UNLOAD").entityName("UnloadConfirmation")
                        .entityId("14").newValues(Map.of("packageId", 1L, "stopId", 40L)).build(),
                AuditLog.builder().id(5L).actionType("PLACEMENT_PINNED").entityName("PackagePlacement")
                        .entityId("500").build(),
                // trỏ tới sai lệch không còn: không có mã
                AuditLog.builder().id(6L).actionType("DEVIATION_RECORDED").entityName("Deviation")
                        .entityId("99").build());
        when(deviationRepository.findAllById(anyIterable()))
                .thenReturn(List.of(Deviation.builder().id(12L).placement(placement).build()));
        when(loadingExecutionRepository.findAllById(anyIterable()))
                .thenReturn(List.of(LoadingExecution.builder().id(13L).loadPlan(plan).build()));
        when(packagePlacementRepository.findAllById(anyIterable())).thenReturn(List.of(placement));
        when(loadPlanRepository.findAllById(anyIterable())).thenReturn(List.of(plan));
        when(deliveryStopRepository.findAllById(anyIterable())).thenReturn(List.of(stop));
        when(tripRepository.findAllById(anyIterable())).thenReturn(List.of(trip));

        List<AuditLogResponse> rows = service.search(NO_FILTER, null, 0, 20).getData();

        assertThat(rows).extracting(AuditLogResponse::getEntityCode).containsExactly(
                "TRIP-2026-0009", "TRIP-2026-0009", "TRIP-2026-0009", "TRIP-2026-0009", "TRIP-2026-0009", null);
        // một truy vấn mỗi bảng cho cả trang; xác nhận xếp có placementId nên không đọc bảng xác nhận
        verify(packagePlacementRepository).findAllById(anyIterable());
        verify(loadPlanRepository).findAllById(anyIterable());
        verify(tripRepository).findAllById(anyIterable());
        verify(placementConfirmationRepository, never()).findAllById(anyIterable());
        verify(unloadConfirmationRepository, never()).findAllById(anyIterable());
    }

    @Test
    void oldLogWithoutCompanyFallsBackToCompanyOfActor() {
        AuditLog old = AuditLog.builder().id(1L).user(user(7, UserRole.DISPATCHER, company(2, "Phương Nam"))).build();
        assertThat(AuditLogQueryService.companyOf(old)).isEqualTo(2L);
        AuditLog system = AuditLog.builder().id(2L).build();
        assertThat(AuditLogQueryService.companyOf(system)).isNull();
        AuditLog onRecord = AuditLog.builder().id(3L).companyId(3L)
                .user(user(8, UserRole.SYSTEM_ADMIN, null)).build();
        assertThat(AuditLogQueryService.companyOf(onRecord)).isEqualTo(3L);
    }

    @Test
    void platformLogHasNoCompanyName() {
        AuditLogResponse row = AuditLogQueryService.toResponse(
                AuditLog.builder().id(1L).actionType("X").build(), id -> "không gọi");
        assertThat(row.getCompanyId()).isNull();
        assertThat(row.getCompanyName()).isNull();
        assertThat(row.getUserId()).isNull();
    }

    @Test
    void newestFirstAndPageSizeIsCapped() {
        when(currentUserService.getCurrentUser()).thenReturn(user(100, UserRole.ADMIN, company(1, "A")));
        returns();

        service.search(NO_FILTER, "9", 2, 1000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(AuditLogQueryService.MAX_PAGE_SIZE);
        assertThat(pageable.getValue().getSort()).containsExactly(
                Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    @Test
    void viewerOutsideTheTwoAdminRolesIsForbiddenBeforeReading() {
        when(currentUserService.getCurrentUser()).thenReturn(user(100, UserRole.MANAGER, company(1, "A")));

        assertThatThrownBy(() -> service.search(NO_FILTER, null, 0, 20))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(auditLogRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void fromAfterToIsBadRequest() {
        AuditLogFilter reversed = new AuditLogFilter(
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1), null, null, null, null);
        assertThatThrownBy(() -> service.search(reversed, null, 0, 20))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
