package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.monitoring.DeadlineStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.monitoring.LocationPointResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.MonitoringBoardResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripExceptionResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.monitoring.EtaRiskAlert;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.monitoring.EtaRiskAlertRepository;
import fu.se184491.loadmaster_be.repository.monitoring.TripExceptionRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripCodeCount;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolPage;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.monitoring.GpsTrackingService;
import fu.se184491.loadmaster_be.service.monitoring.TripExceptionService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.monitoring.TripMonitoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Giám sát chỉ đọc theo công ty của người hỏi: tổng quan xếp chuyến cần chú ý trước, chi tiết một chuyến lấy cảnh báo
 * trễ hạn đã ghi cho đúng hạn hiện tại; không toạ độ, không người báo, không dò theo mã số.
 */
class MonitoringToolTest {

    private static final long COMPANY = 7L;
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 11, 24, 10, 0);

    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final TripMonitoringService monitoring = mock(TripMonitoringService.class);
    private final GpsTrackingService gps = mock(GpsTrackingService.class);
    private final TripExceptionService exceptions = mock(TripExceptionService.class);
    private final TripLookupHelper lookup = mock(TripLookupHelper.class);
    private final DeliveryStopRepository stops = mock(DeliveryStopRepository.class);
    private final EtaRiskAlertRepository alerts = mock(EtaRiskAlertRepository.class);
    private final TripExceptionRepository exceptionCounts = mock(TripExceptionRepository.class);
    private final MonitoringTool tool = new MonitoringTool(currentUser, monitoring, gps, exceptions, lookup, stops,
            alerts, exceptionCounts, TransactionOperations.withoutTransaction());

    @BeforeEach
    void signedIn() {
        when(currentUser.getCurrentCompanyId()).thenReturn(COMPANY);
    }

    @Test
    void onlyDispatchersAndManagersGetIt() {
        assertThat(tool.roles()).containsExactlyInAnyOrder(UserRole.DISPATCHER, UserRole.MANAGER);
    }

    @Test
    void overviewPutsTripsWithIncidentsAndRiskFirstAndReadsTheCallersCompanyOnly() {
        when(monitoring.getDashboard(COMPANY)).thenReturn(MonitoringBoardResponse.builder().trips(List.of(
                trip("TRIP-A", stop(1, "Dĩ An", "2026-11-24T08:00:00"), stop(2, "Thủ Đức", null)),
                trip("TRIP-B", stop(1, "Biên Hoà", null)))).build());
        when(alerts.countStopsAtRiskByTrip(eq(COMPANY), eq(TripStatus.IN_TRANSIT), any(), any()))
                .thenReturn(List.of(count("TRIP-B", 1)));
        when(exceptionCounts.countByTripAndStatusIn(eq(COMPANY), eq(TripStatus.IN_TRANSIT), any()))
                .thenReturn(List.of(count("TRIP-B", 2)));
        when(gps.getLatestLocation("TRIP-A", COMPANY)).thenReturn(LocationPointResponse.builder().lat(10.9)
                .lng(106.8).source("SIMULATED").recordedAt("2026-11-24T08:30:00").build());
        when(gps.getLatestLocation("TRIP-B", COMPANY)).thenThrow(new AppException(ErrorCode.TRIP_NOT_FOUND));

        ToolPage<MonitoringTool.TripRow> page = tool.getMonitoringOverview();

        assertThat(page.rows()).extracting(MonitoringTool.TripRow::tripCode).containsExactly("TRIP-B", "TRIP-A");
        MonitoringTool.TripRow a = page.rows().get(1);
        assertThat(a.stopsDone()).isEqualTo(1);
        assertThat(a.stopsTotal()).isEqualTo(2);
        assertThat(a.nextStopName()).isEqualTo("Thủ Đức");
        assertThat(a.location().source()).isEqualTo("SIMULATED");
        assertThat(page.rows().get(0).stopsAtRisk()).isEqualTo(1);
        assertThat(page.rows().get(0).location()).isNull();
        String json = ToolResults.json(page);
        assertThat(json).doesNotContain("106.8").doesNotContain("10.9").doesNotContain("\"lat\"");
        verify(monitoring).getDashboard(COMPANY);
    }

    @Test
    void overviewReadsAtMostTenLocations() {
        List<MonitoringBoardResponse.MonitoringTripResponse> many = java.util.stream.IntStream.range(0, 14)
                .mapToObj(i -> trip("TRIP-" + (100 + i), stop(1, "Điểm", null))).toList();
        when(monitoring.getDashboard(COMPANY)).thenReturn(MonitoringBoardResponse.builder().trips(many).build());

        tool.getMonitoringOverview();

        verify(gps, times(MonitoringTool.MAX_LOCATIONS)).getLatestLocation(anyString(), eq(COMPANY));
    }

    @Test
    void tripMonitoringKeepsOnlyTheAlertForTheCurrentDeadlineAndHidesWhoReported() {
        Trip trip = Trip.builder().id(55L).tripCode("TRIP-A").tripName("Biên Hoà — Dĩ An")
                .status(TripStatus.IN_TRANSIT).vehicle(Vehicle.builder().name("Xe 3,5 tấn").licensePlate("51C-1")
                        .build()).driver(User.builder().fullName("Phạm Văn Tài").build()).build();
        when(lookup.requireTrip("TRIP-A", COMPANY)).thenReturn(trip);
        DeliveryStop first = DeliveryStop.builder().id(1L).stopSequence(1).stopName("Dĩ An")
                .status(DeliveryStopStatus.PENDING).plannedArrival(DEADLINE).build();
        DeliveryStop second = DeliveryStop.builder().id(2L).stopSequence(2).stopName("Thủ Đức")
                .status(DeliveryStopStatus.PENDING).plannedArrival(DEADLINE.plusHours(2)).build();
        when(stops.findByTripIdOrderByStopSequenceAsc(55L)).thenReturn(List.of(first, second));
        when(alerts.findByTripIdOrderByCreatedAtAsc(55L)).thenReturn(List.of(
                EtaRiskAlert.builder().stopId(1L).status(DeadlineStatus.AT_RISK).deadline(DEADLINE)
                        .eta(DEADLINE.minusMinutes(5)).build(),
                // Hạn đã được gia hạn sau cảnh báo này: cảnh báo cũ không còn đúng.
                EtaRiskAlert.builder().stopId(2L).status(DeadlineStatus.MISSED).deadline(DEADLINE.plusHours(1))
                        .eta(DEADLINE.plusHours(3)).build()));
        when(exceptions.list("TRIP-A", COMPANY)).thenReturn(List.of(
                TripExceptionResponse.builder().id("91").type("TRAFFIC").status("OPEN").delayMinutes(20)
                        .reportedBy("1234").reportedAt("2026-11-24T08:40:00").build(),
                TripExceptionResponse.builder().id("92").type("OTHER").status("RESOLVED").build()));

        Object out = tool.getTripMonitoring(" trip-a ");

        assertThat(out).isInstanceOf(MonitoringTool.TripMonitoring.class);
        MonitoringTool.TripMonitoring result = (MonitoringTool.TripMonitoring) out;
        assertThat(result.vehicle()).isEqualTo("Xe 3,5 tấn (51C-1)");
        assertThat(result.stops().get(0).risk()).isEqualTo(DeadlineStatus.AT_RISK);
        assertThat(result.stops().get(1).risk()).isNull();
        assertThat(result.unresolvedExceptions()).hasSize(1);
        assertThat(result.resolvedExceptions()).isEqualTo(1);
        String json = ToolResults.json(out);
        assertThat(json).doesNotContain("1234").doesNotContain("\"id\"").doesNotContain("reportedBy");
    }

    @Test
    void tripOfAnotherCompanyOrANumericIdIsNotFound() {
        when(lookup.requireTrip("TRIP-PN", COMPANY)).thenThrow(new AppException(ErrorCode.TRIP_NOT_FOUND));

        assertThat(tool.getTripMonitoring("TRIP-PN")).isEqualTo(ToolResults.error("TRIP_NOT_FOUND"));
        assertThat(tool.getTripMonitoring("55")).isEqualTo(ToolResults.error("TRIP_NOT_FOUND"));
        assertThat(tool.getTripMonitoring(" ")).isEqualTo(ToolResults.error("TRIP_NOT_FOUND"));
        verify(lookup, never()).requireTrip(eq("55"), any());
    }

    private static MonitoringBoardResponse.MonitoringTripResponse trip(String code,
                                                                     MonitoringBoardResponse.MonitoringStopResponse... stops) {
        return MonitoringBoardResponse.MonitoringTripResponse.builder().tripId(code).name("Tuyến " + code)
                .vehicleName("Xe 3,5 tấn").driverName("Phạm Văn Tài").stops(List.of(stops)).build();
    }

    private static MonitoringBoardResponse.MonitoringStopResponse stop(int number, String name, String completedAt) {
        return MonitoringBoardResponse.MonitoringStopResponse.builder().id(String.valueOf(number)).number(number)
                .name(name).lat(10.9).lng(106.8).completedAt(completedAt).build();
    }

    private static TripCodeCount count(String code, long total) {
        return new TripCodeCount() {
            @Override
            public String getTripCode() {
                return code;
            }

            @Override
            public Long getTotal() {
                return total;
            }
        };
    }
}
