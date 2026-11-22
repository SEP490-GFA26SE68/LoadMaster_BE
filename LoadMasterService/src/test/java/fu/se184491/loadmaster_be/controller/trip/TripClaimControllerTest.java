package fu.se184491.loadmaster_be.controller.trip;

import fu.se184491.loadmaster_be.constant.trip.TripClaimStatus;
import fu.se184491.loadmaster_be.dto.request.trip.RejectTripClaimRequest;
import fu.se184491.loadmaster_be.dto.response.trip.TripClaimResponse;
import fu.se184491.loadmaster_be.service.trip.TripClaimService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TripClaimControllerTest {

    @Test
    @DisplayName("JSON phiếu xin chuyến định dạng createdAt và decidedAt như phiếu hỗ trợ")
    void serializesClaimTimesWithSupportTicketFormat() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        TripClaimResponse claim = TripClaimResponse.builder()
                .createdAt(LocalDateTime.of(2026, 11, 20, 21, 5, 33))
                .decidedAt(LocalDateTime.of(2026, 11, 20, 21, 10, 0))
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(claim));

        assertEquals("2026-11-20 21:05:33", json.get("createdAt").asText());
        assertEquals("2026-11-20 21:10:00", json.get("decidedAt").asText());
        assertTrue(!json.get("createdAt").asText().contains("T"));
        assertTrue(!json.get("decidedAt").asText().contains("T"));
    }

    @Test
    @DisplayName("JSON phiếu xin chuyến giữ decidedAt null")
    void serializesNullDecidedAt() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TripClaimResponse claim = TripClaimResponse.builder()
                .createdAt(LocalDateTime.of(2026, 11, 20, 21, 5, 33))
                .decidedAt(null)
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(claim));

        assertTrue(json.get("decidedAt").isNull());
    }

    @Test
    @DisplayName("GET /api/trips/{tripId}/claims trả về danh sách phiếu xin chuyến")
    void getClaims_returnsList() throws Exception {
        TripClaimService service = mock(TripClaimService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TripClaimController(service)).build();

        TripClaimResponse claim = TripClaimResponse.builder()
                .id(1L)
                .tripId(10L)
                .driverUserId(100L)
                .driverName("Tài xế A")
                .status(TripClaimStatus.PENDING)
                .note("Nhận chuyến nhé")
                .createdAt(LocalDateTime.now())
                .build();

        when(service.getTripClaims(10L)).thenReturn(List.of(claim));

        mockMvc.perform(get("/api/trips/10/claims"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1L))
                .andExpect(jsonPath("$.data[0].driverName").value("Tài xế A"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("POST /api/trips/{tripId}/claims/{claimId}/approve duyệt thành công")
    void approveClaim_success() throws Exception {
        TripClaimService service = mock(TripClaimService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TripClaimController(service)).build();

        TripClaimResponse approved = TripClaimResponse.builder()
                .id(1L)
                .tripId(10L)
                .driverUserId(100L)
                .status(TripClaimStatus.APPROVED)
                .build();

        when(service.approveClaim(10L, 1L)).thenReturn(approved);

        mockMvc.perform(post("/api/trips/10/claims/1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    @DisplayName("POST /api/trips/{tripId}/claims/{claimId}/reject từ chối không có lý do -> 400 Bad Request")
    void rejectClaim_rejectsBlankReason_with400() throws Exception {
        TripClaimService service = mock(TripClaimService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TripClaimController(service)).build();

        // Lý do trống
        mockMvc.perform(post("/api/trips/10/claims/1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());

        // Body thiếu reason
        mockMvc.perform(post("/api/trips/10/claims/1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/trips/{tripId}/claims/{claimId}/reject từ chối với lý do quá 300 ký tự -> 400 Bad Request")
    void rejectClaim_rejectsTooLongReason_with400() throws Exception {
        TripClaimService service = mock(TripClaimService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TripClaimController(service)).build();

        String longReason = "a".repeat(301);

        mockMvc.perform(post("/api/trips/10/claims/1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + longReason + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/trips/{tripId}/claims/{claimId}/reject từ chối hợp lệ -> 200 OK")
    void rejectClaim_success() throws Exception {
        TripClaimService service = mock(TripClaimService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TripClaimController(service)).build();

        TripClaimResponse rejected = TripClaimResponse.builder()
                .id(1L)
                .tripId(10L)
                .driverUserId(100L)
                .status(TripClaimStatus.REJECTED)
                .rejectReason("Không đủ điều kiện")
                .build();

        when(service.rejectClaim(eq(10L), eq(1L), any(RejectTripClaimRequest.class))).thenReturn(rejected);

        mockMvc.perform(post("/api/trips/10/claims/1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Không đủ điều kiện\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReason").value("Không đủ điều kiện"));
    }
}
