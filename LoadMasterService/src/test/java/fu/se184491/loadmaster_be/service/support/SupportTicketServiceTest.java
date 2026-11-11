package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
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
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyAgreementRepository;
import fu.se184491.loadmaster_be.repository.support.SupportTicketRepository;
import fu.se184491.loadmaster_be.service.support.impl.SupportTicketServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportTicketServiceTest {

    @Mock
    private SupportTicketRepository supportTicketRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private fu.se184491.loadmaster_be.repository.common.AuditLogRepository auditLogRepository;

    @Mock
    private CompanySubscriptionRepository companySubscriptionRepository;

    @Mock
    private CompanyAgreementRepository companyAgreementRepository;

    @Mock
    private fu.se184491.loadmaster_be.repository.support.TicketReplyRepository ticketReplyRepository;

    @InjectMocks
    private SupportTicketServiceImpl supportTicketService;

    private Company company10;
    private User companyAdmin;
    private User systemSupporter;

    @BeforeEach
    void setUp() {
        company10 = Company.builder()
                .id(10L)
                .companyName("Acme Logistics")
                .build();

        companyAdmin = User.builder()
                .id(2L)
                .email("admin@acme.vn")
                .fullName("Acme Admin")
                .company(company10)
                .userRoleType(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();

        systemSupporter = User.builder()
                .id(5L)
                .email("supporter@loadmaster.vn")
                .fullName("System Supporter")
                .userRoleType(UserRole.SYSTEM_SUPPORTER)
                .status(UserStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("createTicket - Generates ticketCode with TCK- prefix, sets status OPEN, saves description, and truncates createdAt to seconds")
    void createTicket_generatesTicketCodeAndSavesOpenTicket() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdmin);
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> {
            SupportTicket t = inv.getArgument(0);
            t.setId(101L);
            return t;
        });

        CreateSupportTicketRequest request = new CreateSupportTicketRequest(
                "Yêu cầu gia hạn gói",
                "Gia hạn thêm 1 năm cho công ty Acme",
                TicketCategory.BILLING,
                null
        );

        SupportTicketResponse response = supportTicketService.createTicket(request);

        assertNotNull(response);
        assertEquals(101L, response.getId());
        assertTrue(response.getTicketCode().startsWith("TCK-"));
        assertEquals(TicketStatus.OPEN, response.getStatus());
        assertEquals(TicketPriority.MEDIUM, response.getPriority());
        assertEquals("Gia hạn thêm 1 năm cho công ty Acme", response.getDescription());
        assertEquals(10L, response.getCompanyId());
        LocalDateTime createdAt = response.getCreatedAt();
        assertNotNull(createdAt);
        assertEquals(0, createdAt.getNano(), "createdAt should be truncated to seconds without fractional milliseconds");
    }

    @Test
    @DisplayName("createTicket - Technical category (SYSTEM_BUG / ALGORITHM_ERROR) automatically sets priority HIGH, and TicketPriority only contains MEDIUM and HIGH")
    void createTicket_whenTechnicalCategory_setsPriorityHigh() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdmin);
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> {
            SupportTicket t = inv.getArgument(0);
            t.setId(102L);
            return t;
        });

        CreateSupportTicketRequest request = new CreateSupportTicketRequest(
                "Lỗi không tải được sơ đồ 3D",
                "Màn hình 3D bị trắng khi bấm xem chuyến",
                TicketCategory.SYSTEM_BUG,
                null
        );

        SupportTicketResponse response = supportTicketService.createTicket(request);

        assertNotNull(response);
        assertEquals(TicketCategory.SYSTEM_BUG, response.getCategory());
        assertEquals(TicketPriority.HIGH, response.getPriority());
        assertEquals(List.of(TicketPriority.MEDIUM, TicketPriority.HIGH), List.of(TicketPriority.values()));
    }

    @Test
    @DisplayName("getTickets - Returns scoped tickets for caller")
    @SuppressWarnings("unchecked")
    void getTickets_returnsFilteredTickets() {
        when(currentUserService.getCurrentUser()).thenReturn(systemSupporter);
        SupportTicket ticket = SupportTicket.builder()
                .id(101L)
                .ticketCode("TCK-20261014-1234")
                .requester(companyAdmin)
                .subject("Yêu cầu gia hạn gói")
                .description("Gia hạn thêm 1 năm")
                .category(TicketCategory.BILLING)
                .priority(TicketPriority.HIGH)
                .status(TicketStatus.OPEN)
                .build();
        when(supportTicketRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(ticket));

        List<SupportTicketResponse> result = supportTicketService.getTickets(TicketStatus.OPEN, TicketCategory.BILLING);

        assertEquals(1, result.size());
        assertEquals("TCK-20261014-1234", result.getFirst().getTicketCode());
    }

    @Test
    @DisplayName("getTickets - Company user only gets tickets they sent themselves, not every ticket of the company")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void getTickets_whenCompanyUser_filtersByRequesterNotCompany() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdmin);
        when(supportTicketRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        supportTicketService.getTickets(null, null);

        ArgumentCaptor<Specification<SupportTicket>> captor = ArgumentCaptor.forClass(Specification.class);
        verify(supportTicketRepository).findAll(captor.capture(), any(Sort.class));

        Root root = mock(Root.class);
        Path requesterPath = mock(Path.class);
        Path requesterIdPath = mock(Path.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        when(root.get("requester")).thenReturn(requesterPath);
        when(requesterPath.get("id")).thenReturn(requesterIdPath);

        captor.getValue().toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(cb).equal(requesterIdPath, 2L);
        verify(requesterPath, never()).get("company");
    }

    @Test
    @DisplayName("updateTicket - Updates status to RESOLVED, sets resolutionNote, assignedTo, and resolvedAt truncated to seconds as LocalDateTime")
    void updateTicket_whenResolved_setsResolutionFieldsAndTimestamp() {
        SupportTicket existing = SupportTicket.builder()
                .id(101L)
                .ticketCode("TCK-20261014-1234")
                .requester(companyAdmin)
                .subject("Yêu cầu gia hạn gói")
                .category(TicketCategory.BILLING)
                .priority(TicketPriority.HIGH)
                .status(TicketStatus.OPEN)
                .build();

        when(currentUserService.getCurrentUser()).thenReturn(systemSupporter);
        when(supportTicketRepository.findById(101L)).thenReturn(Optional.of(existing));
        when(userRepository.findById(5L)).thenReturn(Optional.of(systemSupporter));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateSupportTicketRequest request = new UpdateSupportTicketRequest(
                TicketStatus.RESOLVED,
                5L,
                "Đã gia hạn thành công"
        );

        SupportTicketResponse response = supportTicketService.updateTicket(101L, request);

        assertEquals(TicketStatus.RESOLVED, response.getStatus());
        assertEquals(5L, response.getAssignedToId());
        assertEquals("Đã gia hạn thành công", response.getResolutionNote());
        LocalDateTime resolvedAt = response.getResolvedAt();
        assertNotNull(resolvedAt);
        assertEquals(0, resolvedAt.getNano(), "resolvedAt should be truncated to seconds without fractional milliseconds");

        SupportTicket.LocalDateTimeSecondsConverter converter = new SupportTicket.LocalDateTimeSecondsConverter();
        String dbResolved = converter.convertToDatabaseColumn(LocalDateTime.of(2026, 10, 19, 20, 19, 22, 709_000_000));
        assertEquals("2026-10-19 20:19:22", dbResolved);
        assertEquals(LocalDateTime.of(2026, 10, 19, 20, 19, 22), converter.convertToEntityAttribute(dbResolved));
    }

    @Test
    @DisplayName("updateTicket - Seam 1: Keeps only the 2 most recent active chat dates in resolutionNote and formats 'at' as yyyy-MM-dd HH:mm:ss")
    void updateTicket_whenChatSpansThreeActiveDates_prunesOldestDateAndFormatsAtTimestamp() {
        SupportTicket existing = SupportTicket.builder()
                .id(105L)
                .ticketCode("TCK-20261020-5555")
                .requester(companyAdmin)
                .subject("Hỏi về nâng cấp gói")
                .category(TicketCategory.BILLING)
                .priority(TicketPriority.MEDIUM)
                .status(TicketStatus.IN_PROGRESS)
                .resolutionNote("""
                        [
                          {"authorId":"2","authorName":"Acme Admin","authorRole":"companyAdmin","text":"Tin nhắn ngày 20","at":"2026-10-20 09:00:00"},
                          {"authorId":"5","authorName":"System Supporter","authorRole":"systemSupporter","text":"Phản hồi ngày 25","at":"2026-10-25 14:30:00"}
                        ]
                        """)
                .build();

        when(currentUserService.getCurrentUser()).thenReturn(systemSupporter);
        when(supportTicketRepository.findById(105L)).thenReturn(Optional.of(existing));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        String incomingThreeDatesJson = """
                [
                  {"authorId":"2","authorName":"Acme Admin","authorRole":"companyAdmin","text":"Tin nhắn ngày 20","at":"2026-10-20T09:00:00.123Z"},
                  {"authorId":"5","authorName":"System Supporter","authorRole":"systemSupporter","text":"Phản hồi ngày 25","at":"2026-10-25 14:30:00"},
                  {"authorId":"2","authorName":"Acme Admin","authorRole":"companyAdmin","text":"Tin nhắn mới ngày 30","at":"2026-10-30T10:15:20.789"}
                ]
                """;

        UpdateSupportTicketRequest request = new UpdateSupportTicketRequest(
                TicketStatus.IN_PROGRESS,
                null,
                incomingThreeDatesJson
        );

        SupportTicketResponse response = supportTicketService.updateTicket(105L, request);

        String savedNote = response.getResolutionNote();
        assertNotNull(savedNote);
        org.junit.jupiter.api.Assertions.assertFalse(
                savedNote.contains("2026-10-20"),
                "Messages from the oldest active date (2026-10-20) must be pruned when 2 newer active dates exist"
        );
        assertTrue(savedNote.contains("2026-10-25 14:30:00"), "Should retain messages from 2026-10-25");
        assertTrue(savedNote.contains("2026-10-30 10:15:20"), "Should retain and format messages from 2026-10-30 as yyyy-MM-dd HH:mm:ss");
    }

    @Test
    @DisplayName("cleanupClosedTicketChatHistory - Seam 2: Clears resolutionNote for CLOSED/RESOLVED tickets older than 15 days while keeping recent or open tickets")
    void cleanupClosedTicketChatHistory_clearsOnlyClosedTicketsOlderThan15Days() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        SupportTicket closed16DaysAgo = SupportTicket.builder()
                .id(201L)
                .ticketCode("TCK-20261001-0001")
                .requester(companyAdmin)
                .status(TicketStatus.CLOSED)
                .resolvedAt(now.minusDays(16))
                .resolutionNote("[{\"text\":\"Chat cách đây 16 ngày\",\"at\":\"2026-10-01 10:00:00\"}]")
                .build();

        SupportTicket closed5DaysAgo = SupportTicket.builder()
                .id(202L)
                .ticketCode("TCK-20261025-0002")
                .requester(companyAdmin)
                .status(TicketStatus.CLOSED)
                .resolvedAt(now.minusDays(5))
                .resolutionNote("[{\"text\":\"Chat mới đóng 5 ngày\",\"at\":\"2026-10-25 10:00:00\"}]")
                .build();

        SupportTicket stillInProgress = SupportTicket.builder()
                .id(203L)
                .ticketCode("TCK-20261001-0003")
                .requester(companyAdmin)
                .status(TicketStatus.IN_PROGRESS)
                .createdAt(now.minusDays(20))
                .resolutionNote("[{\"text\":\"Ticket chưa đóng\",\"at\":\"2026-10-01 10:00:00\"}]")
                .build();

        // Truy vấn chỉ trả phiếu CLOSED / RESOLVED còn lịch sử; phiếu đang xử lý lọt vào vẫn không bị xoá.
        when(supportTicketRepository.findWithChatHistoryByStatusIn(argThat(statuses ->
                statuses.size() == 2
                        && statuses.contains(TicketStatus.CLOSED)
                        && statuses.contains(TicketStatus.RESOLVED))))
                .thenReturn(List.of(closed16DaysAgo, closed5DaysAgo, stillInProgress));

        int cleanedCount = supportTicketService.cleanupClosedTicketChatHistory();

        assertEquals(1, cleanedCount, "Only 1 closed ticket older than 15 days should have its chat history cleared");
        org.junit.jupiter.api.Assertions.assertNull(closed16DaysAgo.getResolutionNote(), "Closed ticket older than 15 days must have resolutionNote cleared to null");
        assertNotNull(closed5DaysAgo.getResolutionNote(), "Closed ticket under 15 days must retain its resolutionNote");
        assertNotNull(stillInProgress.getResolutionNote(), "Open/In-progress ticket must retain its resolutionNote");
        verify(supportTicketRepository, never()).findAll();
        verify(supportTicketRepository).save(closed16DaysAgo);
        verify(supportTicketRepository, never()).save(closed5DaysAgo);
    }

    @Test
    @DisplayName("cleanupClosedTicketChatHistory - Replies in ticket_replies of an expired ticket are deleted with it; recent ones stay")
    void cleanupClosedTicketChatHistory_deletesTheTableRepliesOfExpiredTickets() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        // Phiếu mới: lịch sử chỉ nằm ở bảng ticket_replies, ghi chú trống
        SupportTicket expiredWithRows = SupportTicket.builder()
                .id(301L).ticketCode("TCK-301").requester(companyAdmin)
                .status(TicketStatus.RESOLVED).resolvedAt(now.minusDays(20)).build();
        SupportTicket recentWithRows = SupportTicket.builder()
                .id(302L).ticketCode("TCK-302").requester(companyAdmin)
                .status(TicketStatus.CLOSED).resolvedAt(now.minusDays(3)).build();
        when(supportTicketRepository.findWithChatHistoryByStatusIn(any()))
                .thenReturn(List.of(expiredWithRows, recentWithRows));
        when(ticketReplyRepository.findTicketIdsWithReplies(List.of(301L, 302L))).thenReturn(java.util.Set.of(301L, 302L));

        int cleanedCount = supportTicketService.cleanupClosedTicketChatHistory();

        assertEquals(1, cleanedCount);
        verify(ticketReplyRepository).deleteByTicketIdIn(List.of(301L));
        // Không có ghi chú để xoá: phiếu không phải lưu lại
        verify(supportTicketRepository, never()).save(any());
    }

    @Test
    @DisplayName("cleanupClosedTicketChatHistory - Runs hourly, not every 10 seconds")
    void cleanupClosedTicketChatHistory_isScheduledHourly() throws Exception {
        org.springframework.scheduling.annotation.Scheduled scheduled = SupportTicketServiceImpl.class
                .getMethod("cleanupClosedTicketChatHistory")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);

        assertNotNull(scheduled);
        assertEquals("0 15 * * * *", scheduled.cron());
        assertEquals(-1L, scheduled.fixedDelay());
    }

    @Test
    @DisplayName("updateTicket - Seam 1b: When a company has tickets across 3 dates (2/11, 3/11, 4/11), updating 4/11 automatically clears 2/11 chat history and leaves other companies untouched")
    void updateTicket_whenCompanyHasTicketsAcrossThreeActiveDates_automaticallyClearsOldestTicketChatHistoryAndKeepsOtherCompanyIntact() {
        SupportTicket ticketNov2 = SupportTicket.builder()
                .id(3L)
                .ticketCode("TCK-20261102-6183")
                .requester(companyAdmin)
                .status(TicketStatus.IN_PROGRESS)
                .resolutionNote("[{\"authorId\":\"4\",\"authorName\":\"Supporter\",\"authorRole\":\"systemSupporter\",\"text\":\"oke tui sẽ giúp\",\"at\":\"2026-11-02 20:26:00\"}]")
                .build();

        SupportTicket ticketNov3 = SupportTicket.builder()
                .id(4L)
                .ticketCode("TCK-20261103-6868")
                .requester(companyAdmin)
                .status(TicketStatus.IN_PROGRESS)
                .resolutionNote("[{\"authorId\":\"8\",\"authorName\":\"Kho\",\"authorRole\":\"warehouse\",\"text\":\"Chat ngày 3/11\",\"at\":\"2026-11-03 09:15:00\"}]")
                .build();

        SupportTicket ticketNov4 = SupportTicket.builder()
                .id(5L)
                .ticketCode("TCK-20261104-9999")
                .requester(companyAdmin)
                .status(TicketStatus.OPEN)
                .build();

        Company otherCompany = Company.builder().id(2L).companyName("Beta Logistics").build();
        User otherCompanyUser = User.builder().id(99L).fullName("Beta Admin").company(otherCompany).build();
        SupportTicket otherCompanyTicketNov2 = SupportTicket.builder()
                .id(6L)
                .ticketCode("TCK-20261102-BETA")
                .requester(otherCompanyUser)
                .status(TicketStatus.IN_PROGRESS)
                .resolutionNote("[{\"authorId\":\"99\",\"authorName\":\"Beta Admin\",\"authorRole\":\"companyAdmin\",\"text\":\"Chat công ty Beta ngày 2/11\",\"at\":\"2026-11-02 10:00:00\"}]")
                .build();

        when(currentUserService.getCurrentUser()).thenReturn(systemSupporter);
        when(supportTicketRepository.findById(5L)).thenReturn(Optional.of(ticketNov4));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(supportTicketRepository.findAll()).thenReturn(List.of(ticketNov2, ticketNov3, ticketNov4, otherCompanyTicketNov2));

        UpdateSupportTicketRequest request = new UpdateSupportTicketRequest(
                TicketStatus.IN_PROGRESS,
                null,
                "[{\"authorId\":\"4\",\"authorName\":\"Supporter\",\"authorRole\":\"systemSupporter\",\"text\":\"Phản hồi ngày 4/11\",\"at\":\"2026-11-04 10:30:00\"}]"
        );

        supportTicketService.updateTicket(5L, request);

        org.junit.jupiter.api.Assertions.assertNull(
                ticketNov2.getResolutionNote(),
                "Company 1 ticket on 2/11 must have its resolutionNote automatically cleared when Company 1 has newer chats on 3/11 and 4/11"
        );
        assertNotNull(ticketNov3.getResolutionNote(), "Company 1 ticket on 3/11 must retain its resolutionNote");
        assertNotNull(ticketNov4.getResolutionNote(), "Company 1 ticket on 4/11 must retain its resolutionNote");
        assertNotNull(otherCompanyTicketNov2.getResolutionNote(), "Company 2 ticket on 2/11 must NOT be affected by Company 1 pruning");
    }

    @Test
    @DisplayName("createTicket & updateTicket - Saves up to 5 images inside resolutionNote JSON and rejects more than 5 images per message")
    void createAndUpdateTicket_preservesUpToFiveImagesInResolutionNoteAndRejectsMoreThanFive() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdmin);
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> {
            SupportTicket t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(301L);
            }
            return t;
        });

        String initialResolutionNote = "[{\"authorId\":\"2\",\"authorName\":\"Acme Admin\",\"authorRole\":\"companyAdmin\",\"text\":\"Đính kèm 2 ảnh lỗi\",\"images\":[\"data:image/jpeg;base64,img1\",\"data:image/jpeg;base64,img2\"],\"at\":\"2026-11-04T20:00:00Z\"}]";
        CreateSupportTicketRequest createReq = new CreateSupportTicketRequest(
                "Hư máy tính",
                "Không mở được màn hình điều phối",
                TicketCategory.SYSTEM_BUG,
                TicketPriority.HIGH,
                initialResolutionNote
        );

        SupportTicketResponse created = supportTicketService.createTicket(createReq);
        assertNotNull(created.getResolutionNote(), "resolutionNote should be saved when creating ticket with images");
        assertTrue(created.getResolutionNote().contains("\"images\":[\"data:image/jpeg;base64,img1\",\"data:image/jpeg;base64,img2\"]"));
        assertTrue(created.getResolutionNote().contains("\"at\":\"2026-11-04 20:00:00\""));

        SupportTicket existingTicket = SupportTicket.builder()
                .id(301L)
                .ticketCode(created.getTicketCode())
                .requester(companyAdmin)
                .status(TicketStatus.OPEN)
                .resolutionNote(created.getResolutionNote())
                .build();
        when(supportTicketRepository.findById(301L)).thenReturn(Optional.of(existingTicket));

        String sixImagesNote = "[{\"authorId\":\"2\",\"authorName\":\"Acme Admin\",\"authorRole\":\"companyAdmin\",\"text\":\"Gửi 6 ảnh\",\"images\":[\"img1\",\"img2\",\"img3\",\"img4\",\"img5\",\"img6\"],\"at\":\"2026-11-04 20:05:00\"}]";
        UpdateSupportTicketRequest invalidUpdate = new UpdateSupportTicketRequest(
                null,
                null,
                sixImagesNote
        );

        AppException ex = assertThrows(
                AppException.class,
                () -> supportTicketService.updateTicket(301L, invalidUpdate),
                "Should reject when a message contains more than 5 images"
        );
        assertEquals(ErrorCode.INVALID_INPUT, ex.getErrorCode());
    }

    @Test
    @DisplayName("updateTicket - Supporter moving OPEN ticket to IN_PROGRESS locks ticket to that supporter and blocks a second supporter")
    void updateTicket_whenSupporterMovesOpenToInProgress_locksTicketAndBlocksSecondSupporter() {
        User secondSupporter = User.builder()
                .id(6L)
                .email("supporter2@loadmaster.vn")
                .fullName("Second Supporter")
                .userRoleType(UserRole.SYSTEM_SUPPORTER)
                .status(UserStatus.ACTIVE)
                .build();

        SupportTicket ticket = SupportTicket.builder()
                .id(401L)
                .ticketCode("TCK-20261109-0401")
                .requester(companyAdmin)
                .subject("Cần kiểm tra tải trọng")
                .category(TicketCategory.SYSTEM_BUG)
                .priority(TicketPriority.HIGH)
                .status(TicketStatus.OPEN)
                .build();

        when(supportTicketRepository.findById(401L)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        // Supporter 1 picks up the OPEN ticket and moves it to IN_PROGRESS
        when(currentUserService.getCurrentUser()).thenReturn(systemSupporter);
        SupportTicketResponse lockedResponse = supportTicketService.updateTicket(
                401L,
                new UpdateSupportTicketRequest(TicketStatus.IN_PROGRESS, null, null)
        );
        assertEquals(TicketStatus.IN_PROGRESS, lockedResponse.getStatus());
        assertEquals(5L, lockedResponse.getAssignedToId(), "Ticket must be locked to Supporter 1 (id=5)");

        // Supporter 2 tries to reply or change status while ticket is IN_PROGRESS -> rejected
        when(currentUserService.getCurrentUser()).thenReturn(secondSupporter);
        AppException lockEx = assertThrows(
                AppException.class,
                () -> supportTicketService.updateTicket(
                        401L,
                        new UpdateSupportTicketRequest(
                                TicketStatus.RESOLVED,
                                null,
                                "[{\"authorId\":\"6\",\"authorName\":\"Second Supporter\",\"authorRole\":\"systemSupporter\",\"text\":\"Để tôi xử lý\",\"at\":\"2026-11-09 10:00:00\"}]"
                        )
                )
        );
        assertEquals(fu.se184491.loadmaster_be.exception.ErrorCode.SUPPORT_TICKET_LOCKED, lockEx.getErrorCode());

        // Client (requester) CAN still reply while ticket is IN_PROGRESS
        when(currentUserService.getCurrentUser()).thenReturn(companyAdmin);
        SupportTicketResponse clientReplyResp = supportTicketService.updateTicket(
                401L,
                new UpdateSupportTicketRequest(
                        null,
                        null,
                        "[{\"authorId\":\"2\",\"authorName\":\"Acme Admin\",\"authorRole\":\"companyAdmin\",\"text\":\"Em gửi thêm thông tin\",\"at\":\"2026-11-09 10:05:00\"}]"
                )
        );
        assertEquals(TicketStatus.IN_PROGRESS, clientReplyResp.getStatus());
        assertEquals(5L, clientReplyResp.getAssignedToId(), "Lock remains with Supporter 1 while IN_PROGRESS");
    }

    @Test
    @DisplayName("updateTicket - RESOLVED ticket is unlocked for any supporter, and when client replies it transitions back to OPEN")
    void updateTicket_whenResolved_anySupporterCanAccess_andClientReplyTransitionsBackToOpen() {
        User secondSupporter = User.builder()
                .id(6L)
                .email("supporter2@loadmaster.vn")
                .fullName("Second Supporter")
                .userRoleType(UserRole.SYSTEM_SUPPORTER)
                .status(UserStatus.ACTIVE)
                .build();

        SupportTicket ticket = SupportTicket.builder()
                .id(402L)
                .ticketCode("TCK-20261109-0402")
                .requester(companyAdmin)
                .assignedTo(systemSupporter)
                .subject("Hỏi về cước phí")
                .category(TicketCategory.BILLING)
                .priority(TicketPriority.MEDIUM)
                .status(TicketStatus.RESOLVED)
                .resolvedAt(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                .build();

        when(supportTicketRepository.findById(402L)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        // Client replies to RESOLVED ticket -> automatically transitions back to OPEN and clears assignedTo lock
        when(currentUserService.getCurrentUser()).thenReturn(companyAdmin);
        SupportTicketResponse reopened = supportTicketService.updateTicket(
                402L,
                new UpdateSupportTicketRequest(
                        null,
                        null,
                        "[{\"authorId\":\"2\",\"authorName\":\"Acme Admin\",\"authorRole\":\"companyAdmin\",\"text\":\"Em vẫn còn thắc mắc thêm\",\"at\":\"2026-11-09 11:00:00\"}]"
                )
        );
        assertEquals(TicketStatus.OPEN, reopened.getStatus(), "Client reply on RESOLVED ticket must move status back to OPEN");
        org.junit.jupiter.api.Assertions.assertNull(reopened.getAssignedToId(), "Reopened OPEN ticket must not be locked to any supporter");

        // Now Supporter 2 can pick it up and move to IN_PROGRESS, locking it to Supporter 2
        when(currentUserService.getCurrentUser()).thenReturn(secondSupporter);
        SupportTicketResponse pickedBySecond = supportTicketService.updateTicket(
                402L,
                new UpdateSupportTicketRequest(TicketStatus.IN_PROGRESS, null, null)
        );
        assertEquals(TicketStatus.IN_PROGRESS, pickedBySecond.getStatus());
        assertEquals(6L, pickedBySecond.getAssignedToId());
    }

    @Test
    @DisplayName("updateTicket - CLOSED ticket is strictly read-only for both supporter and client")
    void updateTicket_whenClosed_rejectsUpdatesFromBothSupporterAndClient() {
        SupportTicket closedTicket = SupportTicket.builder()
                .id(403L)
                .ticketCode("TCK-20261109-0403")
                .requester(companyAdmin)
                .subject("Phiếu đã đóng")
                .category(TicketCategory.SYSTEM_BUG)
                .priority(TicketPriority.HIGH)
                .status(TicketStatus.CLOSED)
                .resolvedAt(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                .build();

        when(supportTicketRepository.findById(403L)).thenReturn(Optional.of(closedTicket));

        when(currentUserService.getCurrentUser()).thenReturn(systemSupporter);
        AppException supporterEx = assertThrows(
                AppException.class,
                () -> supportTicketService.updateTicket(
                        403L,
                        new UpdateSupportTicketRequest(TicketStatus.OPEN, null, null)
                )
        );
        assertEquals(fu.se184491.loadmaster_be.exception.ErrorCode.SUPPORT_TICKET_CLOSED, supporterEx.getErrorCode());

        when(currentUserService.getCurrentUser()).thenReturn(companyAdmin);
        AppException clientEx = assertThrows(
                AppException.class,
                () -> supportTicketService.updateTicket(
                        403L,
                        new UpdateSupportTicketRequest(
                                null,
                                null,
                                "[{\"authorId\":\"2\",\"authorName\":\"Acme Admin\",\"authorRole\":\"companyAdmin\",\"text\":\"Chat vào phiếu đã đóng\",\"at\":\"2026-11-09 12:00:00\"}]"
                        )
                )
        );
        assertEquals(fu.se184491.loadmaster_be.exception.ErrorCode.SUPPORT_TICKET_CLOSED, clientEx.getErrorCode());
    }

    // =========================================================================
    // Quyền trên phiếu: nền tảng mọi phiếu, người khác chỉ phiếu của chính mình
    // =========================================================================

    private SupportTicket openTicketOf(User requester, long id) {
        return SupportTicket.builder()
                .id(id)
                .ticketCode("TCK-20261111-" + id)
                .requester(requester)
                .subject("Không xuất được báo cáo")
                .description("Nút xuất báo cáo không phản hồi")
                .category(TicketCategory.SYSTEM_BUG)
                .priority(TicketPriority.HIGH)
                .status(TicketStatus.OPEN)
                .resolutionNote("[{\"authorId\":\"2\",\"text\":\"Nội dung gốc\",\"at\":\"2026-11-11 08:00:00\"}]")
                .build();
    }

    @Test
    @DisplayName("updateTicket - A coworker in the same company cannot patch someone else's ticket (not found, nothing saved)")
    void updateTicket_whenCoworkerPatchesOthersTicket_rejectedAsNotFound() {
        User coworker = User.builder()
                .id(3L).email("dispatcher@acme.vn").fullName("Acme Dispatcher")
                .company(company10).userRoleType(UserRole.DISPATCHER).status(UserStatus.ACTIVE)
                .build();
        SupportTicket ticket = openTicketOf(companyAdmin, 601L);
        String originalNote = ticket.getResolutionNote();

        when(currentUserService.getCurrentUser()).thenReturn(coworker);
        when(supportTicketRepository.findById(601L)).thenReturn(Optional.of(ticket));

        AppException ex = assertThrows(AppException.class, () -> supportTicketService.updateTicket(
                601L, new UpdateSupportTicketRequest(null, null, "[{\"text\":\"Ghi đè\"}]")));

        assertEquals(ErrorCode.SUPPORT_TICKET_NOT_FOUND, ex.getErrorCode());
        assertEquals(originalNote, ticket.getResolutionNote());
        verify(supportTicketRepository, never()).save(any());
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    @DisplayName("updateTicket - A user of another company cannot patch the ticket, even when it is CLOSED (no state leak)")
    void updateTicket_whenOtherCompanyUserPatchesTicket_rejectedAsNotFound() {
        Company otherCompany = Company.builder().id(20L).companyName("Beta Logistics").build();
        User outsider = User.builder()
                .id(30L).email("admin@beta.vn").fullName("Beta Admin")
                .company(otherCompany).userRoleType(UserRole.ADMIN).status(UserStatus.ACTIVE)
                .build();
        SupportTicket ticket = openTicketOf(companyAdmin, 602L);
        ticket.setStatus(TicketStatus.CLOSED);

        when(currentUserService.getCurrentUser()).thenReturn(outsider);
        when(supportTicketRepository.findById(602L)).thenReturn(Optional.of(ticket));

        AppException ex = assertThrows(AppException.class, () -> supportTicketService.updateTicket(
                602L, new UpdateSupportTicketRequest(null, null, "[{\"text\":\"Xin chào\"}]")));

        assertEquals(ErrorCode.SUPPORT_TICKET_NOT_FOUND, ex.getErrorCode());
        verify(supportTicketRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateTicket - A missing ticket answers the same not-found code as someone else's ticket")
    void updateTicket_whenTicketMissing_returnsTicketNotFound() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdmin);
        when(supportTicketRepository.findById(999L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> supportTicketService.updateTicket(
                999L, new UpdateSupportTicketRequest(null, null, "[{\"text\":\"Xin chào\"}]")));

        assertEquals(ErrorCode.SUPPORT_TICKET_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("updateTicket - The owner can reply on their own ticket")
    void updateTicket_whenOwnerReplies_updatesNote() {
        SupportTicket ticket = openTicketOf(companyAdmin, 603L);
        String reply = "[{\"authorId\":\"2\",\"text\":\"Em gửi thêm ảnh\",\"at\":\"2026-11-11 09:00:00\"}]";

        when(currentUserService.getCurrentUser()).thenReturn(companyAdmin);
        when(supportTicketRepository.findById(603L)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        SupportTicketResponse response = supportTicketService.updateTicket(
                603L, new UpdateSupportTicketRequest(null, null, reply));

        assertTrue(response.getResolutionNote().contains("Em gửi thêm ảnh"));
        assertEquals(TicketStatus.OPEN, response.getStatus());
        verify(supportTicketRepository).save(ticket);
    }

    @Test
    @DisplayName("updateTicket - The owner cannot change status or assignee of their own ticket")
    void updateTicket_whenOwnerChangesStatusOrAssignee_rejected() {
        SupportTicket ticket = openTicketOf(companyAdmin, 604L);

        when(currentUserService.getCurrentUser()).thenReturn(companyAdmin);
        when(supportTicketRepository.findById(604L)).thenReturn(Optional.of(ticket));

        AppException statusEx = assertThrows(AppException.class, () -> supportTicketService.updateTicket(
                604L, new UpdateSupportTicketRequest(TicketStatus.RESOLVED, null, null)));
        AppException assigneeEx = assertThrows(AppException.class, () -> supportTicketService.updateTicket(
                604L, new UpdateSupportTicketRequest(null, 2L, null)));

        assertEquals(ErrorCode.UNAUTHORIZED, statusEx.getErrorCode());
        assertEquals(ErrorCode.UNAUTHORIZED, assigneeEx.getErrorCode());
        assertEquals(TicketStatus.OPEN, ticket.getStatus());
        verify(supportTicketRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateTicket - A system supporter can patch any company's ticket")
    void updateTicket_whenSupporterPatchesAnyTicket_ok() {
        SupportTicket ticket = openTicketOf(companyAdmin, 605L);
        String reply = "[{\"authorId\":\"5\",\"text\":\"Đã kiểm tra\",\"at\":\"2026-11-11 10:00:00\"}]";

        when(currentUserService.getCurrentUser()).thenReturn(systemSupporter);
        when(supportTicketRepository.findById(605L)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        SupportTicketResponse response = supportTicketService.updateTicket(
                605L, new UpdateSupportTicketRequest(null, null, reply));

        assertEquals(TicketStatus.IN_PROGRESS, response.getStatus());
        assertEquals(5L, response.getAssignedToId());
        assertTrue(response.getResolutionNote().contains("Đã kiểm tra"));
        verify(auditLogRepository).save(any());
    }

    @Test
    @DisplayName("unlockTrialAccount - A company user cannot unlock an account through a ticket")
    void unlockTrialAccount_whenCompanyUser_rejected() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdmin);

        AppException ex = assertThrows(AppException.class,
                () -> supportTicketService.unlockTrialAccount(503L, "Tự mở khóa"));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        verifyNoInteractions(supportTicketRepository, userRepository, companyAgreementRepository);
    }

    // =========================================================================
    // RED → GREEN: unlockTrialAccount — TDD Cycle
    // =========================================================================

    @Test
    @DisplayName("unlockTrialAccount - Ticket không phải loại TRIAL_ACCOUNT_UNLOCK → ném UNLOCK_TRIAL_TICKET_NOT_ELIGIBLE")
    void unlockTrialAccount_whenWrongCategory_throwsNotEligible() {
        SupportTicket billingTicket = SupportTicket.builder()
                .id(500L)
                .ticketCode("TCK-20261110-0500")
                .requester(companyAdmin)
                .category(TicketCategory.BILLING)
                .status(TicketStatus.OPEN)
                .build();

        when(currentUserService.getCurrentUser()).thenReturn(systemSupporter);
        when(supportTicketRepository.findById(500L)).thenReturn(Optional.of(billingTicket));

        AppException ex = assertThrows(AppException.class,
                () -> supportTicketService.unlockTrialAccount(500L, "Mở thử"));
        assertEquals(ErrorCode.UNLOCK_TRIAL_TICKET_NOT_ELIGIBLE, ex.getErrorCode());
    }

    @Test
    @DisplayName("unlockTrialAccount - Ticket đã RESOLVED → ném UNLOCK_TRIAL_TICKET_ALREADY_RESOLVED")
    void unlockTrialAccount_whenTicketAlreadyResolved_throwsAlreadyResolved() {
        SupportTicket resolvedTicket = SupportTicket.builder()
                .id(501L)
                .ticketCode("TCK-20261110-0501")
                .requester(companyAdmin)
                .category(TicketCategory.TRIAL_ACCOUNT_UNLOCK)
                .status(TicketStatus.RESOLVED)
                .resolvedAt(LocalDateTime.now().minusHours(1))
                .build();

        when(currentUserService.getCurrentUser()).thenReturn(systemSupporter);
        when(supportTicketRepository.findById(501L)).thenReturn(Optional.of(resolvedTicket));

        AppException ex = assertThrows(AppException.class,
                () -> supportTicketService.unlockTrialAccount(501L, "Thử mở lại"));
        assertEquals(ErrorCode.UNLOCK_TRIAL_TICKET_ALREADY_RESOLVED, ex.getErrorCode());
    }

    @Test
    @DisplayName("unlockTrialAccount - User không ở FLAGGED → ném UNLOCK_TRIAL_ALREADY_ACTIVE")
    void unlockTrialAccount_whenUserNotFlagged_throwsAlreadyActive() {
        User activeUser = User.builder()
                .id(2L).email("admin@acme.vn").fullName("Acme Admin")
                .company(company10).userRoleType(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();
        SupportTicket ticket = SupportTicket.builder()
                .id(502L).ticketCode("TCK-20261110-0502")
                .requester(activeUser)
                .category(TicketCategory.TRIAL_ACCOUNT_UNLOCK)
                .status(TicketStatus.OPEN)
                .build();

        when(currentUserService.getCurrentUser()).thenReturn(systemSupporter);
        when(supportTicketRepository.findById(502L)).thenReturn(Optional.of(ticket));

        AppException ex = assertThrows(AppException.class,
                () -> supportTicketService.unlockTrialAccount(502L, "Thử mở lại"));
        assertEquals(ErrorCode.UNLOCK_TRIAL_ALREADY_ACTIVE, ex.getErrorCode());
    }

    @Test
    @DisplayName("unlockTrialAccount - User FLAGGED, subscription còn hạn → ACTIVE, trialStillValid=true, nextAction=SIGN_AGREEMENT")
    void unlockTrialAccount_whenFlaggedAndTrialValid_returnsSignAgreementAction() {
        User flaggedUser = User.builder()
                .id(2L).email("admin@acme.vn").fullName("Acme Admin")
                .company(company10).userRoleType(UserRole.ADMIN)
                .status(UserStatus.FLAGGED)
                .build();
        SupportTicket ticket = SupportTicket.builder()
                .id(503L).ticketCode("TCK-20261110-0503")
                .requester(flaggedUser)
                .category(TicketCategory.TRIAL_ACCOUNT_UNLOCK)
                .status(TicketStatus.OPEN)
                .build();
        CompanySubscription activeSub = CompanySubscription.builder()
                .company(company10)
                .status(SubscriptionStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(10))
                .build();
        CompanyAgreement expiredAgreement = CompanyAgreement.builder()
                .company(company10).user(flaggedUser)
                .status(AgreementStatus.EXPIRED)
                .deadlineAt(LocalDateTime.now().minusDays(2))
                .title("Thỏa thuận dùng thử").content("Nội dung").agreementCode("AGR-001")
                .build();

        when(currentUserService.getCurrentUser()).thenReturn(systemSupporter);
        when(supportTicketRepository.findById(503L)).thenReturn(Optional.of(ticket));
        when(companySubscriptionRepository.findByCompanyId(10L)).thenReturn(Optional.of(activeSub));
        when(companyAgreementRepository.findFirstByCompanyIdOrderByCreatedAtDesc(10L))
                .thenReturn(Optional.of(expiredAgreement));
        when(supportTicketRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(companyAgreementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UnlockTrialResponse result = supportTicketService.unlockTrialAccount(503L, "Hỗ trợ mở khóa cho khách");

        assertNotNull(result);
        assertEquals(2L, result.getUserId());
        assertTrue(result.isTrialStillValid(), "Subscription còn hạn → trialStillValid phải true");
        assertEquals("SIGN_AGREEMENT", result.getNextAction());
        assertEquals("RESOLVED", result.getTicketStatus());
        assertEquals(UserStatus.ACTIVE, flaggedUser.getStatus());
        assertEquals(AgreementStatus.PENDING, expiredAgreement.getStatus(),
                "Agreement phải được reset về PENDING để khách ký lại trong 72h");
    }

    @Test
    @DisplayName("unlockTrialAccount - User FLAGGED, subscription hết hạn → ACTIVE, trialStillValid=false, nextAction=UPGRADE_PLAN")
    void unlockTrialAccount_whenFlaggedAndTrialExpired_returnsUpgradePlanAction() {
        User flaggedUser = User.builder()
                .id(2L).email("admin@acme.vn").fullName("Acme Admin")
                .company(company10).userRoleType(UserRole.ADMIN)
                .status(UserStatus.FLAGGED)
                .build();
        SupportTicket ticket = SupportTicket.builder()
                .id(504L).ticketCode("TCK-20261110-0504")
                .requester(flaggedUser)
                .category(TicketCategory.TRIAL_ACCOUNT_UNLOCK)
                .status(TicketStatus.IN_PROGRESS)
                .build();
        CompanySubscription expiredSub = CompanySubscription.builder()
                .company(company10)
                .status(SubscriptionStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().minusDays(3))
                .build();

        when(currentUserService.getCurrentUser()).thenReturn(systemSupporter);
        when(supportTicketRepository.findById(504L)).thenReturn(Optional.of(ticket));
        when(companySubscriptionRepository.findByCompanyId(10L)).thenReturn(Optional.of(expiredSub));
        when(supportTicketRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UnlockTrialResponse result = supportTicketService.unlockTrialAccount(504L, "Mở khóa, nhắc khách nâng cấp");

        assertNotNull(result);
        assertFalse(result.isTrialStillValid(), "Subscription hết hạn → trialStillValid phải false");
        assertEquals("UPGRADE_PLAN", result.getNextAction());
        assertEquals("RESOLVED", result.getTicketStatus());
        assertEquals(UserStatus.ACTIVE, flaggedUser.getStatus());
    }
}
