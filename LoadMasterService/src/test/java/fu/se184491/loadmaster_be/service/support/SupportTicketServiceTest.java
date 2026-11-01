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
}
