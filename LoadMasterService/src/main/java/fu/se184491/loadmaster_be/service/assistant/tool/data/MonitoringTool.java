package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.monitoring.DeadlineStatus;
import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopType;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.monitoring.LocationPointResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.MonitoringBoardResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripExceptionResponse;
import fu.se184491.loadmaster_be.entity.monitoring.EtaRiskAlert;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.monitoring.EtaRiskAlertRepository;
import fu.se184491.loadmaster_be.repository.monitoring.TripExceptionRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripCodeCount;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolPage;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.monitoring.GpsTrackingService;
import fu.se184491.loadmaster_be.service.monitoring.TripExceptionService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.monitoring.TripMonitoringService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Giám sát chuyến Đang vận chuyển cho điều phối viên và quản lý công ty, như màn {@code /giam-sat}
 * ({@code hasAnyRole('DISPATCHER','MANAGER')} của {@code MonitoringController}). Chỉ đọc: bảng chuyến đang chạy là
 * {@link TripMonitoringService#getDashboard} ({@code GET /api/dispatcher/dashboard}), vị trí mới nhất là
 * {@link GpsTrackingService#getLatestLocation}, sự cố là {@link TripExceptionService#list}
 * ({@code GET /api/trips/{id}/exceptions}). Nguy cơ trễ hạn là cảnh báo hệ thống đã ghi khi tính lại giờ đến (như bản
 * tin đầu ngày), không gọi dịch vụ chỉ đường và không ghi gì — nên khác {@code GET /api/trips/{id}/monitoring}, vốn tính
 * lại giờ đến và ghi cảnh báo. Mọi lối đọc theo công ty của người đang hỏi. Không trả toạ độ, đường đi, mã số nội bộ
 * hay mã người dùng.
 */
@Component
public class MonitoringTool implements AssistantTool {

    /** Số chuyến đọc thêm vị trí mới nhất trong một lần gọi tổng quan. */
    static final int MAX_LOCATIONS = 10;
    private static final int MAX_CODE_LENGTH = 64;
    private static final Set<TripExceptionStatus> UNRESOLVED =
            EnumSet.of(TripExceptionStatus.OPEN, TripExceptionStatus.ESCALATED);
    private static final Set<DeadlineStatus> RISKY = EnumSet.of(DeadlineStatus.AT_RISK, DeadlineStatus.MISSED);

    private final CurrentUserService currentUser;
    private final TripMonitoringService monitoring;
    private final GpsTrackingService gps;
    private final TripExceptionService exceptions;
    private final TripLookupHelper lookup;
    private final DeliveryStopRepository stops;
    private final EtaRiskAlertRepository alerts;
    private final TripExceptionRepository exceptionCounts;
    private final TransactionOperations transactions;

    @Autowired
    public MonitoringTool(CurrentUserService currentUser, TripMonitoringService monitoring, GpsTrackingService gps,
                          TripExceptionService exceptions, TripLookupHelper lookup, DeliveryStopRepository stops,
                          EtaRiskAlertRepository alerts, TripExceptionRepository exceptionCounts,
                          PlatformTransactionManager transactionManager) {
        this(currentUser, monitoring, gps, exceptions, lookup, stops, alerts, exceptionCounts,
                readOnly(transactionManager));
    }

    MonitoringTool(CurrentUserService currentUser, TripMonitoringService monitoring, GpsTrackingService gps,
                   TripExceptionService exceptions, TripLookupHelper lookup, DeliveryStopRepository stops,
                   EtaRiskAlertRepository alerts, TripExceptionRepository exceptionCounts,
                   TransactionOperations transactions) {
        this.currentUser = currentUser;
        this.monitoring = monitoring;
        this.gps = gps;
        this.exceptions = exceptions;
        this.lookup = lookup;
        this.stops = stops;
        this.alerts = alerts;
        this.exceptionCounts = exceptionCounts;
        this.transactions = transactions;
    }

    private static TransactionTemplate readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    /** Vị trí mới nhất của xe: nguồn {@code GPS} (điện thoại tài xế) hay {@code SIMULATED} (mô phỏng), lúc ghi. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Location(String source, String recordedAt, Double speedKmh) {
    }

    /** Một chuyến đang chạy trong tổng quan. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TripRow(String tripCode, String tripName, String vehicle, String driverName, int stopsDone,
                          int stopsTotal, Integer nextStopNumber, String nextStopName, long stopsAtRisk,
                          long openExceptions, long escalatedExceptions, Location location) {
    }

    /** Một điểm của chuyến; {@code risk} là cảnh báo trễ hạn hệ thống ghi gần nhất cho đúng hạn hiện tại của điểm. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stop(int number, String name, DeliveryStopType type, DeliveryStopStatus status,
                       LocalDateTime deadline, LocalDateTime arrivedAt, LocalDateTime completedAt,
                       DeadlineStatus risk, LocalDateTime riskEta, LocalDateTime riskRecordedAt) {
    }

    /** Sự cố cấp chuyến chưa xử lý; không có người báo (mã người dùng) hay ghi chú nội bộ. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Incident(String type, String status, Integer stopNumber, Integer delayMinutes,
                           String description, String reportedAt, String escalationReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TripMonitoring(String tripCode, TripStatus tripStatus, String tripName, String vehicle,
                                 String driverName, Location location, List<Stop> stops,
                                 List<Incident> unresolvedExceptions, int resolvedExceptions) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.DISPATCHER, UserRole.MANAGER);
    }

    @Tool(name = "get_monitoring_overview", description = """
            Tổng quan màn Giám sát: mọi chuyến Đang vận chuyển của công ty, mỗi chuyến có xe, tài xế, số điểm đã \
            xong / tổng, điểm kế tiếp, số điểm có nguy cơ trễ hạn (stopsAtRisk: hệ thống đã cảnh báo sát hạn hoặc \
            trễ hạn dự kiến), số sự cố chưa xử lý (openExceptions, gồm cả sự cố đã chuyển quản lý) và đã chuyển quản \
            lý (escalatedExceptions), và vị trí mới nhất (nguồn GPS hay mô phỏng, lúc ghi) cho tối đa 10 chuyến. \
            Chuyến cần chú ý đứng trước. Không có toạ độ. Dùng khi hỏi xe nào đang chạy, chuyến nào có sự cố hay \
            nguy cơ trễ; hỏi chi tiết một chuyến thì gọi get_trip_monitoring. Công cụ này chỉ đếm sự cố: có chuyến \
            mang sự cố chưa xử lý thì gọi tiếp get_trip_monitoring cho chuyến đó để nói sự cố gì, ở đâu, chậm bao \
            lâu.""")
    public ToolPage<TripRow> getMonitoringOverview() {
        Long companyId = currentUser.getCurrentCompanyId();
        MonitoringBoardResponse board = monitoring.getDashboard(companyId);
        List<MonitoringBoardResponse.MonitoringTripResponse> trips =
                board == null || board.getTrips() == null ? List.of() : board.getTrips();
        Map<String, Long> risk = byTrip(alerts.countStopsAtRiskByTrip(companyId, TripStatus.IN_TRANSIT, RISKY,
                DeliveryStopStatus.COMPLETED));
        Map<String, Long> open = byTrip(exceptionCounts.countByTripAndStatusIn(companyId, TripStatus.IN_TRANSIT,
                UNRESOLVED));
        Map<String, Long> escalated = byTrip(exceptionCounts.countByTripAndStatusIn(companyId,
                TripStatus.IN_TRANSIT, EnumSet.of(TripExceptionStatus.ESCALATED)));
        List<TripRow> rows = trips.stream()
                .map(trip -> rowOf(trip, risk, open, escalated))
                .sorted(Comparator.comparingLong(TripRow::escalatedExceptions).reversed()
                        .thenComparing(Comparator.comparingLong(TripRow::openExceptions).reversed())
                        .thenComparing(Comparator.comparingLong(TripRow::stopsAtRisk).reversed())
                        .thenComparing(TripRow::tripCode, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int[] reads = {0};
        List<TripRow> withLocation = rows.stream()
                .map(row -> reads[0]++ < MAX_LOCATIONS ? withLocation(row, companyId) : row)
                .toList();
        return ToolResults.page(withLocation, ToolResults.MAX_ROWS);
    }

    @Tool(name = "get_trip_monitoring", description = """
            Giám sát một chuyến của công ty theo mã chuyến (TRIP-…): vị trí mới nhất của xe (nguồn GPS hay mô \
            phỏng, lúc ghi, tốc độ), từng điểm theo thứ tự đi — hạn (giờ đến kế hoạch của tuyến), đã đến / đã hoàn \
            tất lúc nào, và cảnh báo trễ hạn gần nhất hệ thống đã ghi (risk AT_RISK sát hạn, MISSED trễ hạn dự \
            kiến, kèm giờ đến dự kiến lúc cảnh báo) —, cùng các sự cố cấp chuyến chưa xử lý (OPEN mới báo, \
            ESCALATED đã chuyển quản lý). Không có risk nghĩa là chưa có cảnh báo, không phải chắc chắn kịp; giờ đến \
            trực tiếp xem ở màn Giám sát. Không có toạ độ hay đường đi. Chuyến không tồn tại hoặc của công ty khác \
            trả lỗi TRIP_NOT_FOUND.""")
    public Object getTripMonitoring(
            @ToolParam(description = "Mã chuyến dạng TRIP-…, như người dùng đưa hoặc từ kết quả công cụ khác")
            String tripCode) {
        String code = tripCode == null ? "" : tripCode.strip().toUpperCase(Locale.ROOT);
        // Mã số nội bộ không phải mã chuyến người dùng thấy: không cho dò theo số.
        if (code.isEmpty() || code.length() > MAX_CODE_LENGTH || code.chars().allMatch(Character::isDigit)) {
            return ToolResults.error(ErrorCode.TRIP_NOT_FOUND.name());
        }
        Long companyId = currentUser.getCurrentCompanyId();
        try {
            return Objects.requireNonNull(transactions.execute(status -> monitoringOf(code, companyId)));
        } catch (AppException e) {
            return ToolResults.error(e);
        }
    }

    private TripMonitoring monitoringOf(String code, Long companyId) {
        Trip trip = lookup.requireTrip(code, companyId);
        List<DeliveryStop> tripStops = stops.findByTripIdOrderByStopSequenceAsc(trip.getId());
        Map<Long, EtaRiskAlert> latestAlert = latestAlerts(trip.getId());
        List<Stop> stopRows = tripStops.stream().map(stop -> stopOf(stop, latestAlert.get(stop.getId()))).toList();
        List<TripExceptionResponse> all = exceptions.list(trip.getTripCode(), companyId);
        List<Incident> unresolved = all.stream()
                .filter(exception -> isUnresolved(exception.getStatus()))
                .map(MonitoringTool::incidentOf)
                .toList();
        return new TripMonitoring(trip.getTripCode(), trip.getStatus(), trip.getTripName(), vehicleOf(trip),
                trip.getDriver() != null ? trip.getDriver().getFullName() : null,
                locationOf(trip.getTripCode(), companyId), stopRows, unresolved, all.size() - unresolved.size());
    }

    /** Cảnh báo gần nhất mỗi điểm; chỉ dùng khi đúng hạn hiện tại của điểm (hạn đã gia hạn thì cảnh báo cũ bỏ). */
    private Map<Long, EtaRiskAlert> latestAlerts(Long tripId) {
        Map<Long, EtaRiskAlert> latest = new HashMap<>();
        for (EtaRiskAlert alert : alerts.findByTripIdOrderByCreatedAtAsc(tripId)) {
            if (alert.getStopId() != null && RISKY.contains(alert.getStatus())) {
                latest.put(alert.getStopId(), alert);
            }
        }
        return latest;
    }

    private static Stop stopOf(DeliveryStop stop, EtaRiskAlert alert) {
        boolean open = stop.getStatus() != DeliveryStopStatus.COMPLETED && stop.getActualArrival() == null;
        boolean current = alert != null && open && Objects.equals(alert.getDeadline(), stop.getPlannedArrival());
        return new Stop(stop.getStopSequence() == null ? 0 : stop.getStopSequence(), stop.getStopName(),
                stop.getStopType() != null ? stop.getStopType() : DeliveryStopType.DELIVERY, stop.getStatus(),
                stop.getPlannedArrival(), stop.getActualArrival(), stop.getCompletedAt(),
                current ? alert.getStatus() : null, current ? alert.getEta() : null,
                current ? alert.getCreatedAt() : null);
    }

    private static Incident incidentOf(TripExceptionResponse exception) {
        return new Incident(exception.getType(), exception.getStatus(), exception.getStopNumber(),
                exception.getDelayMinutes(), exception.getDescription(), exception.getReportedAt(),
                exception.getEscalation() != null ? exception.getEscalation().getReason() : null);
    }

    private static boolean isUnresolved(String status) {
        return TripExceptionStatus.OPEN.name().equals(status) || TripExceptionStatus.ESCALATED.name().equals(status);
    }

    private TripRow rowOf(MonitoringBoardResponse.MonitoringTripResponse trip, Map<String, Long> risk,
                          Map<String, Long> open, Map<String, Long> escalated) {
        List<MonitoringBoardResponse.MonitoringStopResponse> tripStops =
                trip.getStops() == null ? List.of() : trip.getStops();
        int done = (int) tripStops.stream().filter(stop -> stop.getCompletedAt() != null).count();
        MonitoringBoardResponse.MonitoringStopResponse next = tripStops.stream()
                .filter(stop -> stop.getCompletedAt() == null).findFirst().orElse(null);
        String code = trip.getTripId();
        return new TripRow(code, trip.getName(), trip.getVehicleName(), trip.getDriverName(), done, tripStops.size(),
                next != null ? next.getNumber() : null, next != null ? next.getName() : null,
                risk.getOrDefault(code, 0L), open.getOrDefault(code, 0L), escalated.getOrDefault(code, 0L), null);
    }

    private TripRow withLocation(TripRow row, Long companyId) {
        Location location = locationOf(row.tripCode(), companyId);
        return location == null ? row : new TripRow(row.tripCode(), row.tripName(), row.vehicle(), row.driverName(),
                row.stopsDone(), row.stopsTotal(), row.nextStopNumber(), row.nextStopName(), row.stopsAtRisk(),
                row.openExceptions(), row.escalatedExceptions(), location);
    }

    private Location locationOf(String tripCode, Long companyId) {
        if (tripCode == null) {
            return null;
        }
        try {
            LocationPointResponse point = gps.getLatestLocation(tripCode, companyId);
            return point == null ? null : new Location(point.getSource(), point.getRecordedAt(), point.getSpeedKmh());
        } catch (AppException e) {
            return null;
        }
    }

    private static String vehicleOf(Trip trip) {
        if (trip.getVehicle() == null) {
            return null;
        }
        String name = trip.getVehicle().getName();
        String plate = trip.getVehicle().getLicensePlate();
        return plate == null ? name : name == null ? plate : name + " (" + plate + ")";
    }

    private static Map<String, Long> byTrip(Collection<TripCodeCount> rows) {
        return rows == null ? Map.of() : rows.stream()
                .filter(row -> row.getTripCode() != null)
                .collect(Collectors.toMap(TripCodeCount::getTripCode,
                        row -> row.getTotal() == null ? 0L : row.getTotal(), Long::sum));
    }
}
