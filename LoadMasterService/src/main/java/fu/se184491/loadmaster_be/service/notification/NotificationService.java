package fu.se184491.loadmaster_be.service.notification;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.dto.response.notification.NotificationListResponse;
import fu.se184491.loadmaster_be.dto.response.notification.NotificationResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.notification.NotificationRead;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.notification.NotificationReadRepository;
import fu.se184491.loadmaster_be.service.audit.AuditLogQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chuông thông báo của người đang đăng nhập. Nguồn là nhật ký hoạt động, tính lúc đọc: không có bảng thông báo
 * ghi sẵn cho từng người, nên một loại việc mới chỉ cần được ghi nhật ký là có thể vào chuông. Đã đọc lưu ở
 * {@code notification_reads}.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** Số dòng nhật ký xét mỗi lần đọc trước khi lọc theo người; đủ cho 7 ngày của một công ty. */
    static final int CANDIDATES = 500;

    private final CurrentUserService currentUserService;
    private final AuditLogQueryService auditLogQueryService;
    private final NotificationReadRepository notificationReadRepository;

    @Transactional(readOnly = true)
    public NotificationListResponse list() {
        return listFor(currentUserService.getCurrentUser());
    }

    /** Đánh dấu đã đọc; mã không phải thông báo của người này thì bỏ qua. Trả lại danh sách mới. */
    @Transactional
    public NotificationListResponse markRead(List<Long> ids) {
        User viewer = currentUserService.getCurrentUser();
        List<Visible> visible = visibleFor(viewer);
        Set<Long> wanted = new HashSet<>(ids == null ? List.of() : ids);
        List<Long> toMark = visible.stream()
                .map(v -> v.log().getId())
                .filter(wanted::contains)
                .toList();
        if (!toMark.isEmpty()) {
            Set<Long> already = new HashSet<>(notificationReadRepository.findReadIds(viewer.getId(), toMark));
            Instant now = Instant.now();
            List<NotificationRead> rows = toMark.stream()
                    .filter(id -> !already.contains(id))
                    .map(id -> NotificationRead.builder().userId(viewer.getId()).auditLogId(id).readAt(now).build())
                    .toList();
            notificationReadRepository.saveAll(rows);
        }
        return respond(viewer, visible);
    }

    private NotificationListResponse listFor(User viewer) {
        return respond(viewer, visibleFor(viewer));
    }

    private record Visible(AuditLog log, Trip trip) {
    }

    private List<Visible> visibleFor(User viewer) {
        UserRole role = viewer.getUserRoleType();
        Set<String> actions = NotificationRules.actionsFor(role);
        if (actions.isEmpty()) {
            return List.of();
        }
        Long companyId = viewer.getCompany() == null ? null : viewer.getCompany().getId();
        // Quản trị hệ thống đọc cả hệ thống; vai trò khác chỉ công ty của mình, không có công ty thì không có gì
        if (role != UserRole.SYSTEM_ADMIN && companyId == null) {
            return List.of();
        }
        Instant since = Instant.now().minus(Duration.ofDays(NotificationRules.WINDOW_DAYS));
        List<AuditLog> logs = auditLogQueryService.recent(
                role == UserRole.SYSTEM_ADMIN ? null : companyId, actions, since, CANDIDATES);
        Map<AuditLog, Trip> trips = auditLogQueryService.tripsOf(logs);
        NotificationRules.Viewer who = new NotificationRules.Viewer(viewer.getId(), role);
        List<Visible> result = new ArrayList<>();
        for (AuditLog log : logs) {
            Trip trip = trips.get(log);
            Long tripDriver = trip == null || trip.getDriver() == null ? null : trip.getDriver().getId();
            Long actorId = log.getUser() == null ? null : log.getUser().getId();
            NotificationRules.Event event = new NotificationRules.Event(
                    log.getActionType(), actorId, log.getOldValues(), log.getNewValues(), tripDriver);
            if (NotificationRules.concerns(event, who)) {
                result.add(new Visible(log, trip));
                if (result.size() >= NotificationRules.LIMIT) {
                    break;
                }
            }
        }
        return result;
    }

    private NotificationListResponse respond(User viewer, List<Visible> visible) {
        List<Long> ids = visible.stream().map(v -> v.log().getId()).toList();
        Set<Long> read = ids.isEmpty()
                ? Set.of()
                : new HashSet<>(notificationReadRepository.findReadIds(viewer.getId(), ids));
        List<NotificationResponse> items = visible.stream()
                .map(v -> toResponse(v, read.contains(v.log().getId())))
                .toList();
        int unread = (int) items.stream().filter(item -> !item.isRead()).count();
        return NotificationListResponse.builder()
                .items(items)
                .unreadCount(unread)
                .windowDays(NotificationRules.WINDOW_DAYS)
                .build();
    }

    private static NotificationResponse toResponse(Visible visible, boolean read) {
        AuditLog log = visible.log();
        User actor = log.getUser();
        Trip trip = visible.trip();
        return NotificationResponse.builder()
                .id(log.getId())
                .actionType(log.getActionType())
                .entityName(log.getEntityName())
                .entityId(log.getEntityId())
                .tripId(trip == null ? null : trip.getId())
                .tripCode(trip == null ? null : trip.getTripCode())
                .actorId(actor == null ? null : actor.getId())
                .actorName(actor == null ? null : actor.getFullName())
                .actorRole(actor == null || actor.getUserRoleType() == null ? null : actor.getUserRoleType().name())
                .oldValues(log.getOldValues())
                .newValues(log.getNewValues())
                .createdAt(log.getCreatedAt())
                .read(read)
                .build();
    }
}
