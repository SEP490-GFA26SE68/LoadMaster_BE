package fu.se184491.loadmaster_be.service.dashboard;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.dto.response.dashboard.ManagerDashboardResponse;
import fu.se184491.loadmaster_be.dto.response.dashboard.ManagerDashboardResponse.DayFill;
import fu.se184491.loadmaster_be.dto.response.dashboard.ManagerDashboardResponse.RecentTrip;
import fu.se184491.loadmaster_be.dto.response.dashboard.ManagerDashboardResponse.VehicleWeight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Tính số liệu bảng điều khiển từ dữ kiện đã đọc của từng chuyến. Hàm thuần, không đọc DB.
 */
public final class ManagerDashboardCalculator {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Số chuyến gần đây mặc định và tối đa của một lần đọc. */
    public static final int RECENT_TRIPS = 10;
    public static final int MAX_RECENT_TRIPS = 50;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ManagerDashboardCalculator() {
    }

    /**
     * Dữ kiện của một chuyến trong kỳ.
     *
     * @param approvedVolumeUtilization tỷ lệ thể tích (0–1) của phương án đã duyệt; null khi chưa có
     * @param deliveredWeightKg         tổng khối lượng kiện Đã giao của chuyến
     * @param cleanItems                kiện Đã giao ở các điểm đã hoàn tất
     * @param finishedItems             mọi kiện ở các điểm đã hoàn tất
     */
    public record TripFacts(
            Long tripId,
            String tripCode,
            String tripName,
            LocalDateTime departureTime,
            TripStatus status,
            Long vehicleId,
            String vehicleName,
            String driverName,
            BigDecimal approvedVolumeUtilization,
            BigDecimal deliveredWeightKg,
            long cleanItems,
            long finishedItems
    ) {
        boolean countsForFill() {
            return status != TripStatus.CANCELLED && approvedVolumeUtilization != null;
        }
    }

    public static ManagerDashboardResponse compute(
            LocalDate from,
            LocalDate to,
            List<TripFacts> trips,
            List<VehicleStatus> vehicles
    ) {
        return compute(from, to, trips, vehicles, RECENT_TRIPS);
    }

    /** Số chuyến gần đây đọc được: 1 → {@link #MAX_RECENT_TRIPS}. */
    public static int clampRecentLimit(int requested) {
        return Math.max(1, Math.min(requested, MAX_RECENT_TRIPS));
    }

    /** @param recentLimit số chuyến của {@code recentTrips}, kẹp về 1 → {@link #MAX_RECENT_TRIPS}. */
    public static ManagerDashboardResponse compute(
            LocalDate from,
            LocalDate to,
            List<TripFacts> trips,
            List<VehicleStatus> vehicles,
            int recentLimit
    ) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (TripStatus status : TripStatus.values()) {
            byStatus.put(status.name(), trips.stream().filter(t -> t.status() == status).count());
        }

        List<TripFacts> filled = trips.stream().filter(TripFacts::countsForFill).toList();
        long clean = trips.stream().mapToLong(TripFacts::cleanItems).sum();
        long finished = trips.stream().mapToLong(TripFacts::finishedItems).sum();

        return ManagerDashboardResponse.builder()
                .from(from)
                .to(to)
                .tripCount(trips.size())
                .completedCount(byStatus.get(TripStatus.DELIVERED.name()))
                .cancelledCount(byStatus.get(TripStatus.CANCELLED.name()))
                .tripsByStatus(byStatus)
                .fill(ManagerDashboardResponse.Fill.builder()
                        .averagePercent(averagePercent(filled))
                        .planCount(filled.size())
                        .build())
                .deliveredWeightKg(totalWeight(trips))
                .delivery(ManagerDashboardResponse.Delivery.builder()
                        .cleanPercent(finished == 0 ? null : percent(BigDecimal.valueOf(clean), BigDecimal.valueOf(finished)))
                        .cleanItems(clean)
                        .finishedItems(finished)
                        .build())
                .vehicles(vehicleCounts(vehicles))
                .fillByDay(fillByDay(filled))
                .weightByVehicle(weightByVehicle(trips))
                .recentTrips(recentTrips(trips, recentLimit))
                .build();
    }

    private static ManagerDashboardResponse.Vehicles vehicleCounts(List<VehicleStatus> vehicles) {
        return ManagerDashboardResponse.Vehicles.builder()
                .total(vehicles.size())
                .inService(vehicles.stream().filter(s -> s == VehicleStatus.IN_TRIP).count())
                .available(vehicles.stream().filter(s -> s == VehicleStatus.AVAILABLE).count())
                .maintenance(vehicles.stream().filter(s -> s == VehicleStatus.MAINTENANCE).count())
                .retired(vehicles.stream().filter(s -> s == VehicleStatus.RETIRED).count())
                .build();
    }

    private static List<DayFill> fillByDay(List<TripFacts> filled) {
        Map<LocalDate, List<TripFacts>> byDay = new TreeMap<>();
        for (TripFacts trip : filled) {
            byDay.computeIfAbsent(trip.departureTime().toLocalDate(), d -> new ArrayList<>()).add(trip);
        }
        List<DayFill> days = new ArrayList<>();
        byDay.forEach((date, dayTrips) -> days.add(DayFill.builder()
                .date(date)
                .averagePercent(averagePercent(dayTrips))
                .planCount(dayTrips.size())
                .build()));
        return days;
    }

    private static List<VehicleWeight> weightByVehicle(List<TripFacts> trips) {
        Map<Long, List<TripFacts>> byVehicle = new LinkedHashMap<>();
        for (TripFacts trip : trips) {
            if (trip.vehicleId() != null) {
                byVehicle.computeIfAbsent(trip.vehicleId(), id -> new ArrayList<>()).add(trip);
            }
        }
        List<VehicleWeight> rows = new ArrayList<>();
        byVehicle.forEach((vehicleId, vehicleTrips) -> rows.add(VehicleWeight.builder()
                .vehicleId(vehicleId)
                .vehicleName(vehicleTrips.get(0).vehicleName())
                .tripCount(vehicleTrips.size())
                .deliveredWeightKg(totalWeight(vehicleTrips))
                .averageFillPercent(averagePercent(vehicleTrips.stream().filter(TripFacts::countsForFill).toList()))
                .build()));
        rows.sort(Comparator.comparing(VehicleWeight::getDeliveredWeightKg).reversed()
                .thenComparing(VehicleWeight::getVehicleName, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    private static List<RecentTrip> recentTrips(List<TripFacts> trips, int recentLimit) {
        return trips.stream()
                .sorted(Comparator.comparing(TripFacts::departureTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TripFacts::tripId, Comparator.reverseOrder()))
                .limit(clampRecentLimit(recentLimit))
                .map(trip -> RecentTrip.builder()
                        .tripId(trip.tripId())
                        .tripCode(trip.tripCode())
                        .tripName(trip.tripName())
                        .departureTime(trip.departureTime() == null ? null
                                : trip.departureTime().atZone(VIETNAM).toOffsetDateTime())
                        .status(trip.status() == null ? null : trip.status().name())
                        .vehicleName(trip.vehicleName())
                        .driverName(trip.driverName())
                        .fillPercent(trip.approvedVolumeUtilization() == null ? null
                                : trip.approvedVolumeUtilization().multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP))
                        .build())
                .toList();
    }

    /** Trung bình tỷ lệ thể tích (%) của các chuyến; null khi không có chuyến nào. */
    private static BigDecimal averagePercent(List<TripFacts> filled) {
        if (filled.isEmpty()) {
            return null;
        }
        BigDecimal sum = filled.stream()
                .map(TripFacts::approvedVolumeUtilization)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return percent(sum, BigDecimal.valueOf(filled.size()));
    }

    private static BigDecimal totalWeight(List<TripFacts> trips) {
        return trips.stream()
                .map(TripFacts::deliveredWeightKg)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }
}
