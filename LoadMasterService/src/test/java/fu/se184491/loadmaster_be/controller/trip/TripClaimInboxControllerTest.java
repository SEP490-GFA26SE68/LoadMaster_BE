package fu.se184491.loadmaster_be.controller.trip;

import fu.se184491.loadmaster_be.dto.response.trip.TripClaimInboxResponse;
import fu.se184491.loadmaster_be.exception.GlobalExceptionHandler;
import fu.se184491.loadmaster_be.service.trip.TripClaimService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TripClaimInboxControllerTest {

    @Test
    @DisplayName("GET /api/trip-claims trả hộp thư và định dạng thời gian không có chữ T")
    void getInbox_returnsClaimsWithFormattedTimes() throws Exception {
        TripClaimService service = mock(TripClaimService.class);
        TripClaimInboxResponse claim = TripClaimInboxResponse.builder()
                .claimId(101L)
                .tripId(201L)
                .tripCode("TRIP-201")
                .tripName("North route")
                .departureTime(LocalDateTime.of(2026, 11, 21, 8, 30, 0))
                .originWarehouse("Warehouse North")
                .stopCount(3)
                .driverUserId(301L)
                .driverName("Driver One")
                .driverPhone("0912345678")
                .note("Please assign this trip")
                .createdAt(LocalDateTime.of(2026, 11, 20, 21, 5, 33))
                .build();
        when(service.listPendingClaims()).thenReturn(List.of(claim));
        MockMvc mockMvc = standaloneMockMvc(service);

        mockMvc.perform(get("/api/trip-claims").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].claimId").value(101L))
                .andExpect(jsonPath("$.data[0].tripId").value(201L))
                .andExpect(jsonPath("$.data[0].tripCode").value("TRIP-201"))
                .andExpect(jsonPath("$.data[0].tripName").value("North route"))
                .andExpect(jsonPath("$.data[0].departureTime").value("2026-11-21 08:30:00"))
                .andExpect(jsonPath("$.data[0].originWarehouse").value("Warehouse North"))
                .andExpect(jsonPath("$.data[0].stopCount").value(3))
                .andExpect(jsonPath("$.data[0].driverUserId").value(301L))
                .andExpect(jsonPath("$.data[0].driverName").value("Driver One"))
                .andExpect(jsonPath("$.data[0].driverPhone").value("0912345678"))
                .andExpect(jsonPath("$.data[0].note").value("Please assign this trip"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-11-20 21:05:33"));

        verify(service).listPendingClaims();
    }

    @Test
    @DisplayName("GET /api/trip-claims từ chối status khác PENDING bằng 400")
    void getInbox_rejectsUnsupportedStatus() throws Exception {
        TripClaimService service = mock(TripClaimService.class);
        MockMvc mockMvc = standaloneMockMvc(service);

        mockMvc.perform(get("/api/trip-claims").param("status", "APPROVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        mockMvc.perform(get("/api/trip-claims").param("status", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("GET /api/trip-claims không truyền status mặc định vẫn trả 200")
    void getInbox_defaultsStatusToPending() throws Exception {
        TripClaimService service = mock(TripClaimService.class);
        when(service.listPendingClaims()).thenReturn(List.of());
        MockMvc mockMvc = standaloneMockMvc(service);

        mockMvc.perform(get("/api/trip-claims"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(service).listPendingClaims();
    }

    private MockMvc standaloneMockMvc(TripClaimService service) {
        return MockMvcBuilders.standaloneSetup(new TripClaimInboxController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
