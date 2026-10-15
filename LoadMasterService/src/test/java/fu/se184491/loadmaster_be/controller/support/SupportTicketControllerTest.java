package fu.se184491.loadmaster_be.controller.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketPriority;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.dto.request.support.CreateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.request.support.UpdateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketResponse;
import fu.se184491.loadmaster_be.service.support.SupportTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SupportTicketController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class SupportTicketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupportTicketService supportTicketService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private ObjectMapper objectMapper;
    private SupportTicketResponse sampleResponse;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sampleResponse = SupportTicketResponse.builder()
                .id(1L)
                .ticketCode("TCK-20261014-0001")
                .subject("Yêu cầu nâng cấp gói Enterprise")
                .description("Công ty cần nâng cấp lên gói Enterprise từ tháng 11")
                .category(TicketCategory.BILLING)
                .priority(TicketPriority.HIGH)
                .status(TicketStatus.OPEN)
                .requesterId(2L)
                .requesterName("Acme Admin")
                .requesterEmail("admin@acme.vn")
                .companyId(10L)
                .companyName("Acme Logistics")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("POST /api/support-tickets - Any authenticated user can create a support ticket")
    @WithMockUser(roles = "ADMIN")
    void createTicket_whenAuthenticated_returnsCreated() throws Exception {
        CreateSupportTicketRequest request = new CreateSupportTicketRequest(
                "Yêu cầu nâng cấp gói Enterprise",
                "Công ty cần nâng cấp lên gói Enterprise từ tháng 11",
                TicketCategory.BILLING,
                TicketPriority.HIGH
        );
        when(supportTicketService.createTicket(any(CreateSupportTicketRequest.class))).thenReturn(sampleResponse);

        mockMvc.perform(post("/api/support-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ticketCode").value("TCK-20261014-0001"))
                .andExpect(jsonPath("$.data.category").value("BILLING"))
                .andExpect(jsonPath("$.data.status").value("OPEN"));

        verify(supportTicketService).createTicket(any(CreateSupportTicketRequest.class));
    }

    @Test
    @DisplayName("GET /api/support-tickets - Returns filtered tickets for caller")
    @WithMockUser(roles = "SYSTEM_SUPPORTER")
    void getTickets_returnsTicketList() throws Exception {
        when(supportTicketService.getTickets(TicketStatus.OPEN, TicketCategory.BILLING))
                .thenReturn(List.of(sampleResponse));

        mockMvc.perform(get("/api/support-tickets")
                        .param("status", "OPEN")
                        .param("category", "BILLING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].ticketCode").value("TCK-20261014-0001"));
    }

    @Test
    @DisplayName("PATCH /api/support-tickets/{id} - Allows SYSTEM_SUPPORTER to update status and resolutionNote")
    @WithMockUser(roles = "SYSTEM_SUPPORTER")
    void updateTicket_whenSystemSupporter_returnsOk() throws Exception {
        UpdateSupportTicketRequest request = new UpdateSupportTicketRequest(
                TicketStatus.RESOLVED,
                5L,
                "Đã kích hoạt gói Enterprise theo hợp đồng"
        );
        SupportTicketResponse resolvedResponse = SupportTicketResponse.builder()
                .id(1L)
                .ticketCode("TCK-20261014-0001")
                .status(TicketStatus.RESOLVED)
                .assignedToId(5L)
                .resolutionNote("Đã kích hoạt gói Enterprise theo hợp đồng")
                .resolvedAt(Instant.now())
                .build();

        when(supportTicketService.updateTicket(eq(1L), any(UpdateSupportTicketRequest.class)))
                .thenReturn(resolvedResponse);

        mockMvc.perform(patch("/api/support-tickets/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolutionNote").value("Đã kích hoạt gói Enterprise theo hợp đồng"));
    }

    @Test
    @DisplayName("PATCH /api/support-tickets/{id} - Denies Company ADMIN with 403 Forbidden")
    @WithMockUser(roles = "ADMIN")
    void updateTicket_whenCompanyAdmin_returnsForbidden() throws Exception {
        UpdateSupportTicketRequest request = new UpdateSupportTicketRequest(
                TicketStatus.RESOLVED,
                null,
                "Tự đóng ticket"
        );

        mockMvc.perform(patch("/api/support-tickets/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verify(supportTicketService, never()).updateTicket(any(), any());
    }
}
