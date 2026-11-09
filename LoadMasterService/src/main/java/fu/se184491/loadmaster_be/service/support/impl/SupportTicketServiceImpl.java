package fu.se184491.loadmaster_be.service.support.impl;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketPriority;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.dto.request.support.CreateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.request.support.UpdateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.repository.support.SupportTicketRepository;
import fu.se184491.loadmaster_be.service.support.SupportTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SupportTicketServiceImpl implements SupportTicketService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter CHAT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_ACTIVE_CHAT_DAYS = 2;
    private static final int CLOSED_CHAT_RETENTION_DAYS = 15;
    private static final int MAX_IMAGES_PER_MESSAGE = 5;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SupportTicketRepository supportTicketRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final AuditLogRepository auditLogRepository;

    @Override
    @Transactional
    public SupportTicketResponse createTicket(CreateSupportTicketRequest request) {
        User currentUser = currentUserService.getCurrentUser();

        String normalizedNote = null;
        if (request.resolutionNote() != null && !request.resolutionNote().isBlank()) {
            normalizedNote = normalizeAndPruneResolutionNote(request.resolutionNote());
        }

        SupportTicket ticket = SupportTicket.builder()
                .requester(currentUser)
                .ticketCode(generateTicketCode())
                .subject(request.subject())
                .description(request.description())
                .category(request.category())
                .priority(resolvePriority(request.category()))
                .status(TicketStatus.OPEN)
                .resolutionNote(normalizedNote)
                .createdAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS))
                .build();

        SupportTicket saved = supportTicketRepository.save(ticket);
        if (normalizedNote != null) {
            pruneCompanyChatHistoryToRecentDays(saved);
        }
        return toResponse(saved);
    }

    @Override
    @Transactional
    public List<SupportTicketResponse> getTickets(TicketStatus status, TicketCategory category) {
        User currentUser = currentUserService.getCurrentUser();
        Specification<SupportTicket> spec = Specification.unrestricted();

        if (!isPlatformSupportOrManager(currentUser.getUserRoleType())) {
            if (currentUser.getCompany() != null) {
                Long companyId = currentUser.getCompany().getId();
                spec = spec.and((root, query, cb) ->
                        cb.equal(root.get("requester").get("company").get("id"), companyId));
            } else {
                Long userId = currentUser.getId();
                spec = spec.and((root, query, cb) ->
                        cb.equal(root.get("requester").get("id"), userId));
            }
        }

        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }

        if (category != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), category));
        }

        List<SupportTicket> tickets = supportTicketRepository
                .findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));
        cleanupExpiredClosedTickets(tickets);
        return tickets.stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public SupportTicketResponse updateTicket(Long id, UpdateSupportTicketRequest request) {
        SupportTicket ticket = supportTicketRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        if (request.status() != null) {
            ticket.setStatus(request.status());
            if ((request.status() == TicketStatus.RESOLVED || request.status() == TicketStatus.CLOSED)
                    && ticket.getResolvedAt() == null) {
                LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
                if (ticket.getCreatedAt() != null && now.isBefore(ticket.getCreatedAt())) {
                    ticket.setResolvedAt(ticket.getCreatedAt().plusMinutes(5).truncatedTo(ChronoUnit.SECONDS));
                } else {
                    ticket.setResolvedAt(now);
                }
            }
        }

        if (request.assignedToId() != null) {
            User assignee = userRepository.findById(request.assignedToId())
                    .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
            ticket.setAssignedTo(assignee);
        }

        if (request.resolutionNote() != null) {
            ticket.setResolutionNote(normalizeAndPruneResolutionNote(request.resolutionNote()));
        }

        SupportTicket saved = supportTicketRepository.save(ticket);
        if (request.resolutionNote() != null) {
            pruneCompanyChatHistoryToRecentDays(saved);
        }
        auditUpdate(saved, request);
        return toResponse(saved);
    }

    /**
     * Người khác (hỗ trợ khách hàng) cập nhật yêu cầu: ghi nhật ký thuộc công ty của người gửi, mang
     * {@code requestedBy}, để chuông của người gửi nhận được. Người gửi tự cập nhật thì không ghi.
     */
    private void auditUpdate(SupportTicket ticket, UpdateSupportTicketRequest request) {
        User actor = currentUserService.getCurrentUser();
        User requester = ticket.getRequester();
        if (actor == null || requester == null || requester.getId() == null
                || requester.getId().equals(actor.getId())) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("requestedBy", requester.getId());
        values.put("ticketCode", ticket.getTicketCode());
        values.put("status", ticket.getStatus() == null ? null : ticket.getStatus().name());
        values.put("replied", request.resolutionNote() != null);
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

    @Override
    @Transactional
    @Scheduled(fixedDelay = 10000)
    public int cleanupClosedTicketChatHistory() {
        return cleanupExpiredClosedTickets(supportTicketRepository.findAll());
    }

    private int cleanupExpiredClosedTickets(List<SupportTicket> tickets) {
        LocalDateTime cutoff = LocalDateTime.now()
                .truncatedTo(ChronoUnit.SECONDS)
                .minusDays(CLOSED_CHAT_RETENTION_DAYS);
        int cleanedCount = 0;
        for (SupportTicket ticket : tickets) {
            TicketStatus status = ticket.getStatus();
            if (status != TicketStatus.CLOSED && status != TicketStatus.RESOLVED) {
                continue;
            }
            if (ticket.getResolutionNote() == null || ticket.getResolutionNote().isBlank()) {
                continue;
            }
            LocalDateTime closedTime = ticket.getResolvedAt() != null
                    ? ticket.getResolvedAt()
                    : ticket.getCreatedAt();
            if (ticket.getCreatedAt() != null && closedTime != null && closedTime.isBefore(ticket.getCreatedAt())) {
                continue;
            }
            boolean expiredFromNow = closedTime != null && !closedTime.isAfter(cutoff);
            boolean expiredFromCreated = ticket.getCreatedAt() != null
                    && closedTime != null
                    && !closedTime.isBefore(ticket.getCreatedAt().plusDays(CLOSED_CHAT_RETENTION_DAYS));
            if (expiredFromNow || expiredFromCreated) {
                ticket.setResolutionNote(null);
                supportTicketRepository.save(ticket);
                cleanedCount++;
            }
        }
        return cleanedCount;
    }

    private String normalizeAndPruneResolutionNote(String rawNote) {
        String trimmed = rawNote.trim();
        if (!trimmed.startsWith("[")) {
            return rawNote;
        }
        try {
            List<Map<String, Object>> replies = OBJECT_MAPPER.readValue(
                    trimmed,
                    new TypeReference<List<Map<String, Object>>>() {}
            );
            for (Map<String, Object> reply : replies) {
                Object imagesObj = reply.get("images");
                if (imagesObj instanceof List<?> imagesList && imagesList.size() > MAX_IMAGES_PER_MESSAGE) {
                    throw new AppException(ErrorCode.INVALID_INPUT, "Mỗi tin nhắn chỉ được đính kèm tối đa 5 ảnh");
                }
                Object atObj = reply.get("at");
                if (atObj != null) {
                    reply.put("at", formatChatTimestamp(atObj.toString()));
                }
            }
            List<String> recentTwoDates = replies.stream()
                    .map(r -> {
                        Object at = r.get("at");
                        return at != null && at.toString().length() >= 10 ? at.toString().substring(0, 10) : "";
                    })
                    .filter(d -> !d.isEmpty())
                    .distinct()
                    .sorted(Comparator.reverseOrder())
                    .limit(MAX_ACTIVE_CHAT_DAYS)
                    .toList();
            Set<String> allowedDates = Set.copyOf(recentTwoDates);
            List<Map<String, Object>> pruned = replies.stream()
                    .filter(r -> {
                        Object at = r.get("at");
                        if (at == null || at.toString().length() < 10) {
                            return true;
                        }
                        return allowedDates.contains(at.toString().substring(0, 10));
                    })
                    .map(LinkedHashMap::new)
                    .collect(Collectors.toList());
            return OBJECT_MAPPER.writeValueAsString(pruned);
        } catch (AppException e) {
            throw e;
        } catch (Exception ignored) {
            return rawNote;
        }
    }

    private void pruneCompanyChatHistoryToRecentDays(SupportTicket currentTicket) {
        if (currentTicket.getRequester() == null || currentTicket.getRequester().getCompany() == null) {
            return;
        }
        Long companyId = currentTicket.getRequester().getCompany().getId();
        if (companyId == null) {
            return;
        }
        List<SupportTicket> companyTickets = supportTicketRepository.findAll().stream()
                .filter(t -> t.getRequester() != null
                        && t.getRequester().getCompany() != null
                        && companyId.equals(t.getRequester().getCompany().getId()))
                .toList();
        if (companyTickets.isEmpty()) {
            return;
        }

        List<String> allDates = new java.util.ArrayList<>();
        for (SupportTicket t : companyTickets) {
            String note = t.getResolutionNote();
            if (note == null || !note.trim().startsWith("[")) {
                continue;
            }
            try {
                List<Map<String, Object>> replies = OBJECT_MAPPER.readValue(
                        note.trim(),
                        new TypeReference<List<Map<String, Object>>>() {}
                );
                for (Map<String, Object> r : replies) {
                    Object at = r.get("at");
                    if (at != null && at.toString().length() >= 10) {
                        allDates.add(at.toString().substring(0, 10));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        Set<String> allowedDates = allDates.stream()
                .distinct()
                .sorted(Comparator.reverseOrder())
                .limit(MAX_ACTIVE_CHAT_DAYS)
                .collect(Collectors.toSet());
        if (allowedDates.isEmpty()) {
            return;
        }

        for (SupportTicket t : companyTickets) {
            String note = t.getResolutionNote();
            if (note == null || !note.trim().startsWith("[")) {
                continue;
            }
            try {
                List<Map<String, Object>> replies = OBJECT_MAPPER.readValue(
                        note.trim(),
                        new TypeReference<List<Map<String, Object>>>() {}
                );
                List<Map<String, Object>> pruned = replies.stream()
                        .filter(r -> {
                            Object at = r.get("at");
                            if (at == null || at.toString().length() < 10) {
                                return true;
                            }
                            return allowedDates.contains(at.toString().substring(0, 10));
                        })
                        .map(LinkedHashMap::new)
                        .collect(Collectors.toList());
                if (pruned.isEmpty()) {
                    t.setResolutionNote(null);
                    supportTicketRepository.save(t);
                } else if (pruned.size() < replies.size()) {
                    t.setResolutionNote(OBJECT_MAPPER.writeValueAsString(pruned));
                    supportTicketRepository.save(t);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String formatChatTimestamp(String rawAt) {
        String v = rawAt.trim();
        try {
            if (v.length() >= 19 && v.charAt(10) == ' ') {
                return LocalDateTime.parse(v.substring(0, 19), CHAT_TIME_FORMATTER).format(CHAT_TIME_FORMATTER);
            }
            if (v.endsWith("Z") || v.contains("+")) {
                return OffsetDateTime.parse(v).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS).format(CHAT_TIME_FORMATTER);
            }
            return LocalDateTime.parse(v).truncatedTo(ChronoUnit.SECONDS).format(CHAT_TIME_FORMATTER);
        } catch (Exception e) {
            try {
                return LocalDateTime.ofInstant(Instant.parse(v), ZoneOffset.UTC)
                        .truncatedTo(ChronoUnit.SECONDS)
                        .format(CHAT_TIME_FORMATTER);
            } catch (Exception ignored) {
                return v.length() >= 19 ? v.substring(0, 19).replace('T', ' ') : v;
            }
        }
    }

    private boolean isPlatformSupportOrManager(UserRole role) {
        return role == UserRole.SYSTEM_SUPPORTER
                || role == UserRole.SYSTEM_MANAGER
                || role == UserRole.SYSTEM_ADMIN;
    }

    private TicketPriority resolvePriority(TicketCategory category) {
        return category == TicketCategory.BILLING
                ? TicketPriority.MEDIUM
                : TicketPriority.HIGH;
    }

    private String generateTicketCode() {
        String datePart = LocalDate.now().format(DATE_FORMATTER);
        int randomDigits = ThreadLocalRandom.current().nextInt(1000, 10000);
        return "TCK-" + datePart + "-" + randomDigits;
    }

    private SupportTicketResponse toResponse(SupportTicket ticket) {
        User requester = ticket.getRequester();
        User assignedTo = ticket.getAssignedTo();
        return SupportTicketResponse.builder()
                .id(ticket.getId())
                .ticketCode(ticket.getTicketCode())
                .subject(ticket.getSubject())
                .description(ticket.getDescription())
                .category(ticket.getCategory())
                .priority(ticket.getPriority())
                .status(ticket.getStatus())
                .requesterId(requester != null ? requester.getId() : null)
                .requesterName(requester != null ? requester.getFullName() : null)
                .requesterEmail(requester != null ? requester.getEmail() : null)
                .companyId(requester != null && requester.getCompany() != null
                        ? requester.getCompany().getId()
                        : null)
                .companyName(requester != null && requester.getCompany() != null
                        ? requester.getCompany().getCompanyName()
                        : null)
                .assignedToId(assignedTo != null ? assignedTo.getId() : null)
                .assignedToName(assignedTo != null ? assignedTo.getFullName() : null)
                .resolutionNote(ticket.getResolutionNote())
                .createdAt(ticket.getCreatedAt())
                .resolvedAt(ticket.getResolvedAt())
                .build();
    }
}
