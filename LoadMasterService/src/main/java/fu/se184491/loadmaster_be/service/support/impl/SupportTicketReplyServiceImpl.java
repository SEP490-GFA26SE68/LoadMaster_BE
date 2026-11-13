package fu.se184491.loadmaster_be.service.support.impl;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketThreadResponse;
import fu.se184491.loadmaster_be.dto.response.support.TicketReplyResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import fu.se184491.loadmaster_be.entity.support.TicketReply;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.support.SupportTicketRepository;
import fu.se184491.loadmaster_be.repository.support.TicketReplyRepository;
import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.service.support.SupportTicketReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cuộc trao đổi của yêu cầu hỗ trợ ở bảng {@code ticket_replies}. Yêu cầu chưa có dòng nào thì đọc mảng JSON cũ trong
 * {@code resolution_note}; lần trả lời đầu tiên ở bảng chép các lần trả lời cũ đó sang trước (ghi chú cũ giữ nguyên) để cuộc trao đổi
 * không mất đoạn đầu.
 */
@Service
@RequiredArgsConstructor
public class SupportTicketReplyServiceImpl implements SupportTicketReplyService {

    private final SupportTicketRepository supportTicketRepository;
    private final TicketReplyRepository ticketReplyRepository;
    private final CurrentUserService currentUserService;
    private final AuditLogRepository auditLogRepository;

    @Override
    @Transactional(readOnly = true)
    public SupportTicketThreadResponse getThread(Long ticketId) {
        User actor = requireActor();
        SupportTicket ticket = visibleTicket(ticketId, actor);
        return threadOf(ticket, ticketReplyRepository.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId()));
    }

    @Override
    @Transactional
    public SupportTicketThreadResponse reply(Long ticketId, String message) {
        User actor = requireActor();
        SupportTicket ticket = visibleTicket(ticketId, actor);
        boolean platform = isPlatformStaff(actor.getUserRoleType());

        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new AppException(ErrorCode.SUPPORT_TICKET_CLOSED);
        }
        User assignee = ticket.getAssignedTo();
        if (platform && ticket.getStatus() == TicketStatus.IN_PROGRESS && assignee != null && assignee.getId() != null
                && !assignee.getId().equals(actor.getId())) {
            throw new AppException(ErrorCode.SUPPORT_TICKET_LOCKED,
                    "Phiếu hỗ trợ này đang được xử lý bởi nhân viên "
                            + (assignee.getFullName() != null ? assignee.getFullName() : assignee.getEmail())
                            + ". Bạn không thể thao tác trên phiếu này.");
        }
        // Như PATCH: trả lời phiếu Đã giải quyết thì mở lại; nhân sự nền tảng trả lời phiếu Mở thì nhận xử lý.
        if (ticket.getStatus() == TicketStatus.RESOLVED) {
            ticket.setStatus(TicketStatus.OPEN);
            ticket.setAssignedTo(null);
            ticket.setResolvedAt(null);
        }
        if (platform && ticket.getStatus() == TicketStatus.OPEN) {
            ticket.setStatus(TicketStatus.IN_PROGRESS);
            ticket.setAssignedTo(actor);
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        List<TicketReply> rows = new ArrayList<>(ticketReplyRepository.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId()));
        if (rows.isEmpty()) {
            for (TicketReplyResponse legacy : LegacyTicketChat.repliesOf(ticket.getResolutionNote())) {
                rows.add(ticketReplyRepository.save(TicketReply.builder()
                        .ticket(ticket)
                        .authorName(legacy.getAuthorName())
                        .authorRole(legacy.getAuthorRole())
                        .message(legacy.getMessage())
                        .createdAt(legacy.getCreatedAt())
                        .build()));
            }
        }
        rows.add(ticketReplyRepository.save(TicketReply.builder()
                .ticket(ticket)
                .authorId(actor.getId())
                .authorName(actor.getFullName() != null ? actor.getFullName() : actor.getEmail())
                .authorRole(actor.getUserRoleType() == null ? null : actor.getUserRoleType().name())
                .message(message.trim())
                .createdAt(now)
                .build()));
        ticket.setUpdatedAt(now);
        SupportTicket saved = supportTicketRepository.save(ticket);
        audit(saved, actor);
        return threadOf(saved, rows);
    }

    private SupportTicketThreadResponse threadOf(SupportTicket ticket, List<TicketReply> rows) {
        List<TicketReplyResponse> replies = rows.isEmpty()
                ? LegacyTicketChat.repliesOf(ticket.getResolutionNote())
                : rows.stream().map(SupportTicketReplyServiceImpl::replyOf).toList();
        return SupportTicketThreadResponse.builder()
                .ticket(SupportTicketServiceImpl.toResponse(ticket))
                .replies(replies)
                .build();
    }

    private static TicketReplyResponse replyOf(TicketReply row) {
        return TicketReplyResponse.builder()
                .id(row.getId())
                .authorId(row.getAuthorId() == null ? null : row.getAuthorId().toString())
                .authorName(row.getAuthorName())
                .authorRole(row.getAuthorRole())
                .message(row.getMessage())
                .createdAt(row.getCreatedAt())
                .build();
    }

    /** Nhân sự nền tảng mở mọi phiếu; người khác chỉ phiếu của chính mình — phiếu khác là "không tìm thấy" như phiếu không có. */
    private SupportTicket visibleTicket(Long ticketId, User actor) {
        boolean platform = isPlatformStaff(actor.getUserRoleType());
        return supportTicketRepository.findById(ticketId)
                .filter(ticket -> platform || (ticket.getRequester() != null
                        && Objects.equals(ticket.getRequester().getId(), actor.getId())))
                .orElseThrow(() -> new AppException(ErrorCode.SUPPORT_TICKET_NOT_FOUND));
    }

    private User requireActor() {
        User actor = currentUserService.getCurrentUser();
        if (actor == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return actor;
    }

    private static boolean isPlatformStaff(UserRole role) {
        return role == UserRole.SYSTEM_SUPPORTER || role == UserRole.SYSTEM_MANAGER || role == UserRole.SYSTEM_ADMIN;
    }

    /** Như PATCH: người khác người gửi trả lời thì ghi nhật ký thuộc công ty người gửi, mang requestedBy, để chuông người gửi nhận. */
    private void audit(SupportTicket ticket, User actor) {
        User requester = ticket.getRequester();
        if (requester == null || requester.getId() == null || requester.getId().equals(actor.getId())) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("requestedBy", requester.getId());
        values.put("ticketCode", ticket.getTicketCode());
        values.put("status", ticket.getStatus() == null ? null : ticket.getStatus().name());
        values.put("replied", true);
        auditLogRepository.save(AuditLog.builder()
                .companyId(AuditCompany.ofAccount(requester))
                .user(actor)
                .actionType("SUPPORT_TICKET_UPDATED")
                .entityName("SupportTicket")
                .entityId(ticket.getId() == null ? null : ticket.getId().toString())
                .newValues(values)
                .createdAt(Instant.now())
                .build());
    }
}
