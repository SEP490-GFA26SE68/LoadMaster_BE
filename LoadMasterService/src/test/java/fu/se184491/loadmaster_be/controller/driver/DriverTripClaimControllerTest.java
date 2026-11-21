package fu.se184491.loadmaster_be.controller.driver;

import fu.se184491.loadmaster_be.constant.trip.TripClaimStatus;
import fu.se184491.loadmaster_be.dto.request.trip.TripClaimRequest;
import fu.se184491.loadmaster_be.dto.response.trip.OpenTripResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripClaimResponse;
import fu.se184491.loadmaster_be.service.trip.TripClaimService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DriverTripClaimControllerTest {

    @Test
    @DisplayName("GET /api/driver/open-trips trả về danh sách chuyến mở")
    void getOpenTrips_returnsList() throws Exception {
        TripClaimService service = mock(TripClaimService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DriverTripClaimController(service)).build();

        OpenTripResponse trip = new OpenTripResponse(
                1L,
                1L,
                "TRIP-001",
                "Chuyến HN - BN",
                LocalDateTime.now(),
                "Kho Hà Nội",
                3,
                null,
                null
        );

        when(service.getOpenTrips()).thenReturn(List.of(trip));

        mockMvc.perform(get("/api/driver/open-trips"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tripCode").value("TRIP-001"))
                .andExpect(jsonPath("$.data[0].stopCount").value(3))
                .andExpect(jsonPath("$.data[0].originWarehouse").value("Kho Hà Nội"));
    }

    @Test
    @DisplayName("POST /api/driver/trips/{tripId}/claim gửi phiếu thành công")
    void claimTrip_success() throws Exception {
        TripClaimService service = mock(TripClaimService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DriverTripClaimController(service)).build();

        TripClaimResponse response = TripClaimResponse.builder()
                .id(10L)
                .tripId(1L)
                .driverUserId(101L)
                .status(TripClaimStatus.PENDING)
                .note("Nhận chuyến nhé")
                .build();

        when(service.claimTrip(eq(1L), any(TripClaimRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/driver/trips/1/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Nhận chuyến nhé\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(10L))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("POST /api/driver/trips/{tripId}/claim với note quá 300 ký tự -> 400 Bad Request")
    void claimTrip_rejectsTooLongNote_with400() throws Exception {
        TripClaimService service = mock(TripClaimService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DriverTripClaimController(service)).build();

        String longNote = "b".repeat(301);

        mockMvc.perform(post("/api/driver/trips/1/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"" + longNote + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/driver/trips/{tripId}/claim/cancel rút phiếu thành công")
    void cancelClaim_success() throws Exception {
        TripClaimService service = mock(TripClaimService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DriverTripClaimController(service)).build();

        TripClaimResponse response = TripClaimResponse.builder()
                .id(10L)
                .tripId(1L)
                .status(TripClaimStatus.CANCELLED)
                .build();

        when(service.cancelClaim(1L)).thenReturn(response);

        mockMvc.perform(post("/api/driver/trips/1/claim/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }
}
