package fu.se184491.loadmaster_be.service.support.impl;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketPriority;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.dto.request.support.CreateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.request.support.UpdateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.support.SupportTicketRepository;
import fu.se184491.loadmaster_be.service.support.SupportTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class SupportTicketServiceImpl implements SupportTicketService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SupportTicketRepository supportTicketRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional
    public SupportTicketResponse createTicket(CreateSupportTicketRequest request) {
        User currentUser = currentUserService.getCurrentUser();

        SupportTicket ticket = SupportTicket.builder()
                .requester(currentUser)
                .ticketCode(generateTicketCode())
                .subject(request.subject())
                .description(request.description())
                .category(request.category())
                .priority(resolvePriority(request.category()))
                .status(TicketStatus.OPEN)
                .createdAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS))
                .build();

        SupportTicket saved = supportTicketRepository.save(ticket);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
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

        return supportTicketRepository
                .findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
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
                ticket.setResolvedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
            }
        }

        if (request.assignedToId() != null) {
            User assignee = userRepository.findById(request.assignedToId())
                    .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
            ticket.setAssignedTo(assignee);
        }

        if (request.resolutionNote() != null) {
            ticket.setResolutionNote(request.resolutionNote());
        }

        SupportTicket saved = supportTicketRepository.save(ticket);
        return toResponse(saved);
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
