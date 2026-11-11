package fu.se184491.loadmaster_be.service.support.impl;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketPriority;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.dto.request.support.CreateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.request.support.UpdateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketResponse;
import fu.se184491.loadmaster_be.dto.response.support.UnlockTrialResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.repository.company.CompanyAgreementRepository;
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
import java.util.Optional;
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
    /** Số giờ khách được cấp thêm để ký lại thỏa thuận sau khi mở khóa */
    private static final long REOPEN_AGREEMENT_HOURS = 72L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SupportTicketRepository supportTicketRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final AuditLogRepository auditLogRepository;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final CompanyAgreementRepository companyAgreementRepository;

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
        User currentUser = requireActor();
        Specification<SupportTicket> spec = Specification.unrestricted();

        // Nhân sự nền tảng thấy mọi phiếu; người khác chỉ thấy phiếu do chính mình gửi
        // (không phải mọi phiếu của công ty: nội dung trao đổi là của người gửi).
        if (!isPlatformSupportOrManager(currentUser.getUserRoleType())) {
            Long userId = currentUser.getId();
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("requester").get("id"), userId));
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
                .map(SupportTicketServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public SupportTicketResponse updateTicket(Long id, UpdateSupportTicketRequest request) {
        User actor = requireActor();
        boolean actorIsSupporter = isPlatformSupportOrManager(actor.getUserRoleType());
        boolean actorIsClient = !actorIsSupporter;

        // 0. Quyền: nhân sự nền tảng sửa mọi phiếu; người khác chỉ phiếu do chính mình gửi. Phiếu của người
        // khác trả "không tìm thấy" như phiếu không tồn tại, để không lộ mã phiếu nào đang có.
        SupportTicket ticket = supportTicketRepository.findById(id)
                .filter(t -> actorIsSupporter || isRequester(t, actor))
                .orElseThrow(() -> new AppException(ErrorCode.SUPPORT_TICKET_NOT_FOUND));
        if (actorIsClient && (request.status() != null || request.assignedToId() != null)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        // 1. Phiếu đã đóng (CLOSED) chỉ đọc đối với cả nhân viên hỗ trợ và khách hàng
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new AppException(ErrorCode.SUPPORT_TICKET_CLOSED);
        }

        // 2. Khoá chống trùng khi phiếu đang ở trạng thái Đang xử lý (IN_PROGRESS):
        // Nhân viên thứ 2 không được thao tác (đổi trạng thái hoặc trả lời) trên phiếu đã có nhân viên 1 nhận xử lý.
        if (ticket.getStatus() == TicketStatus.IN_PROGRESS
                && ticket.getAssignedTo() != null
                && ticket.getAssignedTo().getId() != null
                && actorIsSupporter
                && !ticket.getAssignedTo().getId().equals(actor.getId())) {
            String assigneeName = ticket.getAssignedTo().getFullName() != null
                    ? ticket.getAssignedTo().getFullName()
                    : ticket.getAssignedTo().getEmail();
            throw new AppException(
                    ErrorCode.SUPPORT_TICKET_LOCKED,
                    "Phiếu hỗ trợ này đang được xử lý bởi nhân viên " + assigneeName + ". Bạn không thể thao tác trên phiếu này."
            );
        }

        // 3. Khi phiếu đang ở Đã giải quyết (RESOLVED) mà khách hàng nhắn tiếp -> tự động mở lại (OPEN) và gỡ khoá
        if (ticket.getStatus() == TicketStatus.RESOLVED
                && request.resolutionNote() != null
                && (actorIsClient || request.status() == null)) {
            ticket.setStatus(TicketStatus.OPEN);
            ticket.setAssignedTo(null);
            ticket.setResolvedAt(null);
        }

        if (request.status() != null) {
            ticket.setStatus(request.status());
            if (request.status() == TicketStatus.OPEN) {
                ticket.setAssignedTo(null);
                ticket.setResolvedAt(null);
            } else if (request.status() == TicketStatus.IN_PROGRESS && actorIsSupporter && request.assignedToId() == null) {
                ticket.setAssignedTo(actor);
            } else if (request.status() == TicketStatus.RESOLVED && request.assignedToId() == null) {
                ticket.setAssignedTo(null);
            }
            if ((request.status() == TicketStatus.RESOLVED || request.status() == TicketStatus.CLOSED)
                    && ticket.getResolvedAt() == null) {
                LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
                if (ticket.getCreatedAt() != null && now.isBefore(ticket.getCreatedAt())) {
                    ticket.setResolvedAt(ticket.getCreatedAt().plusMinutes(5).truncatedTo(ChronoUnit.SECONDS));
                } else {
                    ticket.setResolvedAt(now);
                }
            }
        } else if (actorIsSupporter && ticket.getStatus() == TicketStatus.OPEN && request.resolutionNote() != null) {
            ticket.setStatus(TicketStatus.IN_PROGRESS);
            ticket.setAssignedTo(actor);
        }

        if (request.assignedToId() != null) {
            User assignee = userRepository.findById(request.assignedToId())
                    .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
            ticket.setAssignedTo(assignee);
        }

        if (request.resolutionNote() != null) {
            ticket.setResolutionNote(normalizeAndPruneResolutionNote(request.resolutionNote()));
        }

        ticket.setUpdatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        SupportTicket saved = supportTicketRepository.save(ticket);
        if (request.resolutionNote() != null) {
            pruneCompanyChatHistoryToRecentDays(saved);
        }
        auditUpdate(saved, request, actor);
        return toResponse(saved);
    }

    /** Người đang gọi; không xác định được thì từ chối thay vì coi như người dùng không vai trò. */
    private User requireActor() {
        User actor = currentUserService.getCurrentUser();
        if (actor == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return actor;
    }

    private boolean isRequester(SupportTicket ticket, User user) {
        return ticket.getRequester() != null
                && ticket.getRequester().getId() != null
                && ticket.getRequester().getId().equals(user.getId());
    }

    /**
     * Người khác (hỗ trợ khách hàng) cập nhật yêu cầu: ghi nhật ký thuộc công ty của người gửi, mang
     * {@code requestedBy}, để chuông của người gửi nhận được. Người gửi tự cập nhật thì không ghi.
     */
    private void auditUpdate(SupportTicket ticket, UpdateSupportTicketRequest request, User actor) {
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

    /**
     * Xoá lịch sử trao đổi của phiếu đã đóng / đã giải quyết quá {@value #CLOSED_CHAT_RETENTION_DAYS} ngày.
     * Hạn tính theo ngày nên chạy mỗi giờ là đủ; chỉ đọc phiếu CLOSED / RESOLVED còn lịch sử trao đổi,
     * không đọc cả bảng. Danh sách phiếu (getTickets) vẫn tự dọn phiếu hết hạn lúc đọc.
     */
    @Override
    @Transactional
    @Scheduled(cron = "0 15 * * * *")
    public int cleanupClosedTicketChatHistory() {
        return cleanupExpiredClosedTickets(supportTicketRepository.findByStatusInAndResolutionNoteIsNotNull(
                List.of(TicketStatus.CLOSED, TicketStatus.RESOLVED)));
    }

    // =========================================================================
    // Mở khóa tài khoản dùng thử qua phiếu hỗ trợ
    // =========================================================================

    /**
     * Mở khóa tài khoản dùng thử đang bị FLAGGED thông qua phiếu hỗ trợ.
     * Luồng:
     * 1. Kiểm tra ticket tồn tại, đúng loại TRIAL_ACCOUNT_UNLOCK, chưa RESOLVED/CLOSED.
     * 2. Kiểm tra tài khoản người gửi đang ở FLAGGED.
     * 3. Kiểm tra subscription còn hạn hay đã hết:
     *    - Còn hạn: reset CompanyAgreement về PENDING, gia hạn deadline +72h → user ký lại.
     *    - Hết hạn: chỉ mở ACTIVE, FE điều hướng về trang nâng cấp gói.
     * 4. Chuyển ticket sang RESOLVED và trả về UnlockTrialResponse.
     */
    @Override
    @Transactional
    public UnlockTrialResponse unlockTrialAccount(Long ticketId, String staffNote) {
        // 0. Chỉ nhân sự nền tảng (controller đã chặn; kiểm lại ở đây vì việc này mở khóa tài khoản người khác)
        if (!isPlatformSupportOrManager(requireActor().getUserRoleType())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        // 1. Lấy và kiểm tra ticket
        SupportTicket ticket = supportTicketRepository.findById(ticketId)
                .orElseThrow(() -> new AppException(ErrorCode.SUPPORT_TICKET_NOT_FOUND));

        if (ticket.getCategory() != TicketCategory.TRIAL_ACCOUNT_UNLOCK) {
            throw new AppException(ErrorCode.UNLOCK_TRIAL_TICKET_NOT_ELIGIBLE);
        }
        if (ticket.getStatus() == TicketStatus.RESOLVED || ticket.getStatus() == TicketStatus.CLOSED) {
            throw new AppException(ErrorCode.UNLOCK_TRIAL_TICKET_ALREADY_RESOLVED);
        }

        // 2. Kiểm tra trạng thái người dùng
        User requester = ticket.getRequester();
        if (requester == null || requester.getStatus() != fu.se184491.loadmaster_be.constant.account.UserStatus.FLAGGED) {
            throw new AppException(ErrorCode.UNLOCK_TRIAL_ALREADY_ACTIVE);
        }

        // 3. Kiểm tra subscription
        boolean trialStillValid = false;
        Long companyId = requester.getCompany() != null ? requester.getCompany().getId() : null;

        if (companyId != null) {
            Optional<CompanySubscription> subOpt = companySubscriptionRepository.findByCompanyId(companyId);
            trialStillValid = subOpt.isPresent()
                    && subOpt.get().getExpiresAt() != null
                    && subOpt.get().getExpiresAt().isAfter(LocalDateTime.now());

            if (trialStillValid) {
                // Còn hạn → reset thỏa thuận về PENDING, gia hạn thêm 72h để ký lại
                companyAgreementRepository.findFirstByCompanyIdOrderByCreatedAtDesc(companyId)
                        .ifPresent(agreement -> {
                            agreement.setStatus(AgreementStatus.PENDING);
                            agreement.setDeadlineAt(LocalDateTime.now().plusHours(REOPEN_AGREEMENT_HOURS));
                            agreement.setSignedAt(null);
                            companyAgreementRepository.save(agreement);
                        });
            }
        }

        // 4. Mở khóa tài khoản
        requester.setStatus(fu.se184491.loadmaster_be.constant.account.UserStatus.ACTIVE);
        userRepository.save(requester);

        // 5. Đánh dấu ticket là RESOLVED
        ticket.setStatus(TicketStatus.RESOLVED);
        ticket.setResolvedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        if (staffNote != null && !staffNote.isBlank()) {
            ticket.setResolutionNote(staffNote);
        }
        supportTicketRepository.save(ticket);

        return UnlockTrialResponse.builder()
                .userId(requester.getId())
                .userEmail(requester.getEmail())
                .trialStillValid(trialStillValid)
                .nextAction(trialStillValid ? "SIGN_AGREEMENT" : "UPGRADE_PLAN")
                .ticketCode(ticket.getTicketCode())
                .ticketStatus(TicketStatus.RESOLVED.name())
                .build();
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

    static SupportTicketResponse toResponse(SupportTicket ticket) {
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
                .updatedAt(LegacyTicketChat.lastActivity(ticket))
                .build();
    }
}
