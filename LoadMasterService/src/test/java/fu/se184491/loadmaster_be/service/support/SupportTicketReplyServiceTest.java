package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketThreadResponse;
import fu.se184491.loadmaster_be.dto.response.support.TicketReplyResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import fu.se184491.loadmaster_be.entity.support.TicketReply;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.support.SupportTicketRepository;
import fu.se184491.loadmaster_be.repository.support.TicketReplyRepository;
import fu.se184491.loadmaster_be.service.support.impl.SupportTicketReplyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupportTicketReplyServiceTest {

    @Mock SupportTicketRepository supportTicketRepository;
    @Mock TicketReplyRepository ticketReplyRepository;
    @Mock CurrentUserService currentUserService;
    @Mock AuditLogRepository auditLogRepository;

    @InjectMocks SupportTicketReplyServiceImpl service;

    private User requester;
    private User colleague;
    private User supporter;
    private SupportTicket ticket;
    private final List<TicketReply> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Company company = Company.builder().id(10L).companyName("Acme").build();
        requester = User.builder().id(2L).fullName("Người Gửi").company(company).userRoleType(UserRole.DISPATCHER).build();
        colleague = User.builder().id(3L).fullName("Đồng Nghiệp").company(company).userRoleType(UserRole.MANAGER).build();
        supporter = User.builder().id(5L).fullName("Hỗ Trợ").userRoleType(UserRole.SYSTEM_SUPPORTER).build();
        ticket = SupportTicket.builder().id(7L).ticketCode("TCK-1").requester(requester).subject("Lỗi")
                .status(TicketStatus.OPEN).createdAt(LocalDateTime.of(2026, 11, 1, 8, 0))
                .resolutionNote("[{\"authorId\":\"2\",\"authorName\":\"Người Gửi\",\"authorRole\":\"dispatcher\","
                        + "\"text\":\"Lần đầu\",\"at\":\"2026-11-01 09:00:00\"}]")
                .build();
        when(supportTicketRepository.findById(7L)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(call -> call.getArgument(0));
        when(ticketReplyRepository.findByTicketIdOrderByCreatedAtAscIdAsc(7L)).thenAnswer(call -> List.copyOf(saved));
        when(ticketReplyRepository.save(any(TicketReply.class))).thenAnswer(call -> {
            TicketReply row = call.getArgument(0);
            row.setId((long) saved.size() + 1);
            saved.add(row);
            return row;
        });
    }

    @Test
    void aTicketWithoutRowsReadsTheOldConversation() {
        when(currentUserService.getCurrentUser()).thenReturn(requester);

        SupportTicketThreadResponse thread = service.getThread(7L);

        assertThat(thread.getReplies()).singleElement()
                .extracting(TicketReplyResponse::getId, TicketReplyResponse::getMessage, TicketReplyResponse::getAuthorRole)
                .containsExactly(null, "Lần đầu", "dispatcher");
        assertThat(thread.getTicket().getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 9, 0));
    }

    @Test
    void aColleagueCannotReadSomeoneElsesTicket() {
        when(currentUserService.getCurrentUser()).thenReturn(colleague);

        assertThatThrownBy(() -> service.getThread(7L))
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.SUPPORT_TICKET_NOT_FOUND);
        assertThatThrownBy(() -> service.reply(7L, "Xin chào"))
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.SUPPORT_TICKET_NOT_FOUND);
        verify(ticketReplyRepository, never()).save(any());
    }

    @Test
    void theSupporterReplyKeepsTheOldMessagesTakesTheTicketAndNotifiesTheRequester() {
        when(currentUserService.getCurrentUser()).thenReturn(supporter);

        SupportTicketThreadResponse thread = service.reply(7L, "  Đã nhận  ");

        assertThat(thread.getReplies()).extracting(TicketReplyResponse::getMessage).containsExactly("Lần đầu", "Đã nhận");
        TicketReplyResponse last = thread.getReplies().get(1);
        assertThat(last.getAuthorId()).isEqualTo("5");
        assertThat(last.getAuthorName()).isEqualTo("Hỗ Trợ");
        assertThat(last.getAuthorRole()).isEqualTo("SYSTEM_SUPPORTER");
        assertThat(thread.getTicket().getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(thread.getTicket().getAssignedToId()).isEqualTo(5L);
        assertThat(thread.getTicket().getUpdatedAt()).isEqualTo(last.getCreatedAt());
        // Ghi chú cũ giữ nguyên, không chép đè
        assertThat(ticket.getResolutionNote()).contains("Lần đầu");

        ArgumentCaptor<AuditLog> log = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(log.capture());
        assertThat(log.getValue().getActionType()).isEqualTo("SUPPORT_TICKET_UPDATED");
        assertThat(log.getValue().getCompanyId()).isEqualTo(10L);
        assertThat(log.getValue().getNewValues()).containsEntry("requestedBy", 2L).containsEntry("replied", true);
    }

    @Test
    void theRequesterReopensAResolvedTicketAndAClosedOneTakesNoReply() {
        when(currentUserService.getCurrentUser()).thenReturn(requester);
        ticket.setStatus(TicketStatus.RESOLVED);
        ticket.setResolvedAt(LocalDateTime.of(2026, 11, 2, 8, 0));

        assertThat(service.reply(7L, "Vẫn lỗi").getTicket().getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.getResolvedAt()).isNull();
        verify(auditLogRepository, never()).save(any());

        ticket.setStatus(TicketStatus.CLOSED);
        assertThatThrownBy(() -> service.reply(7L, "Còn đó không"))
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.SUPPORT_TICKET_CLOSED);
    }

    @Test
    void anotherSupporterCannotReplyToATicketInProgress() {
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        ticket.setAssignedTo(User.builder().id(6L).fullName("Người Khác").build());
        when(currentUserService.getCurrentUser()).thenReturn(supporter);

        assertThatThrownBy(() -> service.reply(7L, "Để tôi"))
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.SUPPORT_TICKET_LOCKED);
    }
}
