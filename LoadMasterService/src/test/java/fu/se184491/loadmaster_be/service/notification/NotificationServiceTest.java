package fu.se184491.loadmaster_be.service.notification;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.dto.response.notification.NotificationListResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.notification.NotificationRead;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.notification.NotificationReadRepository;
import fu.se184491.loadmaster_be.service.audit.AuditLogQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock private CurrentUserService currentUserService;
    @Mock private AuditLogQueryService auditLogQueryService;
    @Mock private NotificationReadRepository notificationReadRepository;

    @InjectMocks private NotificationService service;

    private final Company company = Company.builder().id(1L).build();

    private User user(long id, UserRole role, Company of) {
        return User.builder().id(id).userRoleType(role).company(of).fullName("Người " + id).build();
    }

    private AuditLog log(long id, String action, User actor, Map<String, Object> before, Map<String, Object> after) {
        return AuditLog.builder().id(id).actionType(action).user(actor).companyId(1L)
                .oldValues(before).newValues(after).createdAt(Instant.parse("2026-11-05T03:00:00Z")).build();
    }

    @Test
    void dispatcherSeesColleaguesWorkWithTripCodeAndReadState() {
        User me = user(10, UserRole.DISPATCHER, company);
        User colleague = user(20, UserRole.DISPATCHER, company);
        AuditLog mine = log(1, "PLAN_APPROVED", me, null, null);
        AuditLog theirs = log(2, "PLAN_APPROVED", colleague, null, null);
        AuditLog system = log(3, "DELIVERY_ETA_RISK", null, null, Map.of("tripId", 7L));
        Trip trip = Trip.builder().id(7L).tripCode("TRIP-0007").build();
        Map<AuditLog, Trip> trips = new IdentityHashMap<>();
        trips.put(system, trip);
        when(currentUserService.getCurrentUser()).thenReturn(me);
        when(auditLogQueryService.recent(eq(1L), anySet(), any(), anyInt())).thenReturn(List.of(system, theirs, mine));
        when(auditLogQueryService.tripsOf(anyList())).thenReturn(trips);
        when(notificationReadRepository.findReadIds(eq(10L), anyCollection())).thenReturn(List.of(2L));

        NotificationListResponse response = service.list();

        assertThat(response.getItems()).extracting(item -> item.getId()).containsExactly(3L, 2L);
        assertThat(response.getItems().get(0).getTripCode()).isEqualTo("TRIP-0007");
        assertThat(response.getItems().get(0).getActorName()).isNull();
        assertThat(response.getItems().get(1).isRead()).isTrue();
        assertThat(response.getUnreadCount()).isEqualTo(1);
        assertThat(response.getWindowDays()).isEqualTo(NotificationRules.WINDOW_DAYS);
    }

    @Test
    void companyRolesReadOnlyTheirCompanyAndSystemAdminReadsAll() {
        User admin = user(1, UserRole.SYSTEM_ADMIN, null);
        when(currentUserService.getCurrentUser()).thenReturn(admin);
        when(auditLogQueryService.recent(any(), anySet(), any(), anyInt())).thenReturn(List.of());
        service.list();
        verify(auditLogQueryService).recent(isNull(), anySet(), any(), anyInt());

        User companyAdmin = user(2, UserRole.ADMIN, company);
        when(currentUserService.getCurrentUser()).thenReturn(companyAdmin);
        service.list();
        verify(auditLogQueryService).recent(eq(1L), anySet(), any(), anyInt());
    }

    @Test
    void rolesWithoutSourcesOrCompanyGetNothing() {
        when(currentUserService.getCurrentUser()).thenReturn(user(3, UserRole.SYSTEM_SUPPORTER, null));
        assertThat(service.list().getItems()).isEmpty();
        when(currentUserService.getCurrentUser()).thenReturn(user(4, UserRole.DISPATCHER, null));
        assertThat(service.list().getItems()).isEmpty();
        verify(auditLogQueryService, never()).recent(any(), anySet(), any(), anyInt());
    }

    @Test
    void driverOnlyGetsOwnTripUsingCurrentDriverWhenNotRecorded() {
        User me = user(13, UserRole.DRIVER, company);
        User worker = user(12, UserRole.WAREHOUSE_WORKER, company);
        AuditLog mineDone = log(1, "LOADING_COMPLETED", worker, null, Map.of("tripId", 7L));
        AuditLog otherDone = log(2, "LOADING_COMPLETED", worker, null, Map.of("tripId", 8L));
        Map<AuditLog, Trip> trips = new IdentityHashMap<>();
        trips.put(mineDone, Trip.builder().id(7L).driver(me).build());
        trips.put(otherDone, Trip.builder().id(8L).driver(user(99, UserRole.DRIVER, company)).build());
        when(currentUserService.getCurrentUser()).thenReturn(me);
        when(auditLogQueryService.recent(eq(1L), anySet(), any(), anyInt())).thenReturn(List.of(mineDone, otherDone));
        when(auditLogQueryService.tripsOf(anyList())).thenReturn(trips);

        assertThat(service.list().getItems()).extracting(item -> item.getId()).containsExactly(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void markReadOnlyStoresVisibleUnreadNotifications() {
        User me = user(10, UserRole.DISPATCHER, company);
        User colleague = user(20, UserRole.DISPATCHER, company);
        AuditLog a = log(5, "PLAN_APPROVED", colleague, null, null);
        AuditLog b = log(6, "PLAN_APPROVED", colleague, null, null);
        when(currentUserService.getCurrentUser()).thenReturn(me);
        when(auditLogQueryService.recent(eq(1L), anySet(), any(), anyInt())).thenReturn(List.of(a, b));
        when(auditLogQueryService.tripsOf(anyList())).thenReturn(Map.of());
        when(notificationReadRepository.findReadIds(eq(10L), anyCollection())).thenReturn(List.of(6L));

        service.markRead(List.of(5L, 6L, 999L));

        ArgumentCaptor<List<NotificationRead>> saved = ArgumentCaptor.forClass(List.class);
        verify(notificationReadRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(NotificationRead::getAuditLogId).containsExactly(5L);
        assertThat(saved.getValue()).extracting(NotificationRead::getUserId).containsOnly(10L);
        assertThat(Set.copyOf(saved.getValue().stream().map(NotificationRead::getAuditLogId).toList()))
                .doesNotContain(999L);
    }
}
