package fu.se184491.loadmaster_be.controller.driver;

import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.dto.response.driver.DriverTripDetailResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.driver.DriverDeliveryService;
import fu.se184491.loadmaster_be.service.driver.DriverService;
import fu.se184491.loadmaster_be.service.monitoring.GpsTrackingService;
import fu.se184491.loadmaster_be.service.monitoring.TripExceptionService;
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

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DriverController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class DriverControllerTest {
    @MockitoBean private fu.se184491.loadmaster_be.service.optimization.OptimizationAccess optimizationAccess;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriverService driverService;

    @MockitoBean
    private DriverDeliveryService driverDeliveryService;

    @MockitoBean
    private GpsTrackingService gpsTrackingService;

    @MockitoBean
    private TripExceptionService tripExceptionService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Test
    @WithMockUser(roles = "DRIVER")
    void getTripDetailReturnsSuccessResponse() throws Exception {
        DriverTripDetailResponse response = DriverTripDetailResponse.builder()
                .tripId(10L)
                .tripCode("TRP-1001")
                .status("PLANNED")
                .depotName("Kho Tổng")
                .licensePlate("29C-12345")
                .totalStops(2)
                .totalPackages(5)
                .totalWeightKg(BigDecimal.valueOf(150))
                .stops(List.of(
                        DriverTripDetailResponse.StopDetail.builder()
                                .stopId(1L)
                                .stopSequence(1)
                                .stopName("Điểm Giao 1")
                                .address("123 Cầu Giấy")
                                .contactName("Nguyễn Văn A")
                                .contactPhone("0987654321")
                                .build()
                ))
                .build();

        when(driverDeliveryService.getTripDetail("10")).thenReturn(response);

        mockMvc.perform(get("/api/driver/trips/10/detail")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tripId").value(10))
                .andExpect(jsonPath("$.data.tripCode").value("TRP-1001"))
                .andExpect(jsonPath("$.data.status").value("PLANNED"))
                .andExpect(jsonPath("$.data.licensePlate").value("29C-12345"))
                .andExpect(jsonPath("$.data.stops[0].contactName").value("Nguyễn Văn A"))
                .andExpect(jsonPath("$.data.stops[0].contactPhone").value("0987654321"));
    }
}
