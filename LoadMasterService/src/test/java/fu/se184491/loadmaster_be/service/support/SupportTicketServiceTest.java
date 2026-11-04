package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketPriority;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.dto.request.support.CreateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.request.support.UpdateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.support.SupportTicketRepository;
import fu.se184491.loadmaster_be.service.support.impl.SupportTicketServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportTicketServiceTest {

    @Mock
    private SupportTicketRepository supportTicketRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CurrentUserService currentUserService;

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

        when(supportTicketRepository.findAll()).thenReturn(List.of(closed16DaysAgo, closed5DaysAgo, stillInProgress));

        int cleanedCount = supportTicketService.cleanupClosedTicketChatHistory();

        assertEquals(1, cleanedCount, "Only 1 closed ticket older than 15 days should have its chat history cleared");
        org.junit.jupiter.api.Assertions.assertNull(closed16DaysAgo.getResolutionNote(), "Closed ticket older than 15 days must have resolutionNote cleared to null");
        assertNotNull(closed5DaysAgo.getResolutionNote(), "Closed ticket under 15 days must retain its resolutionNote");
        assertNotNull(stillInProgress.getResolutionNote(), "Open/In-progress ticket must retain its resolutionNote");
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
                TicketStatus.IN_PROGRESS,
                null,
                sixImagesNote
        );

        assertThrows(
                AppException.class,
                () -> supportTicketService.updateTicket(301L, invalidUpdate),
                "Should reject when a message contains more than 5 images"
        );
    }
}
