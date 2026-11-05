package fu.se184491.loadmaster_be.service.dashboard;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.dto.response.dashboard.ManagerDashboardResponse;
import fu.se184491.loadmaster_be.service.dashboard.ManagerDashboardCalculator.TripFacts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManagerDashboardCalculatorTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 5);

    private static TripFacts trip(long id, LocalDateTime departure, TripStatus status, Long vehicleId,
                                  String fill, String deliveredKg, int clean, int finished) {
        return new TripFacts(id, "TRIP-" + id, "Tuyến " + id, departure, status, vehicleId,
                vehicleId == null ? null : "Xe " + vehicleId, "Tài xế " + id,
                fill == null ? null : new BigDecimal(fill),
                new BigDecimal(deliveredKg), clean, finished);
    }

    private static LocalDateTime at(int day, int hour) {
        return LocalDateTime.of(2026, 11, day, hour, 0);
    }

    @Test
    void emptyPeriodHasNullAveragesAndZeroCounts() {
        ManagerDashboardResponse r = ManagerDashboardCalculator.compute(FROM, TO, List.of(), List.of());

        assertThat(r.getFrom()).isEqualTo(FROM);
        assertThat(r.getTo()).isEqualTo(TO);
        assertThat(r.getTripCount()).isZero();
        assertThat(r.getTripsByStatus()).containsOnlyKeys(
                "DRAFT", "PLANNED", "LOADING", "IN_TRANSIT", "DELIVERED", "CANCELLED");
        assertThat(r.getTripsByStatus().values()).allMatch(count -> count == 0L);
        assertThat(r.getFill().getAveragePercent()).isNull();
        assertThat(r.getFill().getPlanCount()).isZero();
        assertThat(r.getDelivery().getCleanPercent()).isNull();
        assertThat(r.getDelivery().getFinishedItems()).isZero();
        assertThat(r.getDeliveredWeightKg()).isEqualByComparingTo("0");
        assertThat(r.getFillByDay()).isEmpty();
        assertThat(r.getWeightByVehicle()).isEmpty();
        assertThat(r.getRecentTrips()).isEmpty();
    }

    @Test
    void countsTripsByStatusIncludingCancelled() {
        ManagerDashboardResponse r = ManagerDashboardCalculator.compute(FROM, TO, List.of(
                trip(1, at(1, 8), TripStatus.DELIVERED, 1L, "0.8", "100", 3, 3),
                trip(2, at(2, 8), TripStatus.CANCELLED, 1L, "0.5", "0", 0, 0),
                trip(3, at(3, 8), TripStatus.PLANNED, 2L, null, "0", 0, 0)
        ), List.of());

        assertThat(r.getTripCount()).isEqualTo(3);
        assertThat(r.getCompletedCount()).isEqualTo(1);
        assertThat(r.getCancelledCount()).isEqualTo(1);
        assertThat(r.getTripsByStatus()).containsEntry("DELIVERED", 1L)
                .containsEntry("CANCELLED", 1L).containsEntry("PLANNED", 1L).containsEntry("DRAFT", 0L);
    }

    @Test
    void fillAveragesApprovedPlansOfTripsNotCancelled() {
        ManagerDashboardResponse r = ManagerDashboardCalculator.compute(FROM, TO, List.of(
                trip(1, at(1, 8), TripStatus.DELIVERED, 1L, "0.8", "0", 0, 0),
                trip(2, at(1, 14), TripStatus.IN_TRANSIT, 1L, "0.6", "0", 0, 0),
                trip(3, at(2, 8), TripStatus.CANCELLED, 1L, "0.1", "0", 0, 0),
                trip(4, at(3, 8), TripStatus.DRAFT, 2L, null, "0", 0, 0)
        ), List.of());

        assertThat(r.getFill().getPlanCount()).isEqualTo(2);
        assertThat(r.getFill().getAveragePercent()).isEqualByComparingTo("70.00");
        // Chỉ ngày có số: ngày 2 chỉ có chuyến huỷ, ngày 3 chưa có phương án duyệt.
        assertThat(r.getFillByDay()).hasSize(1);
        assertThat(r.getFillByDay().get(0).getDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(r.getFillByDay().get(0).getAveragePercent()).isEqualByComparingTo("70.00");
        assertThat(r.getFillByDay().get(0).getPlanCount()).isEqualTo(2);
    }

    @Test
    void deliveryRatioIsCleanOverFinishedItems() {
        ManagerDashboardResponse r = ManagerDashboardCalculator.compute(FROM, TO, List.of(
                trip(1, at(1, 8), TripStatus.DELIVERED, 1L, null, "120.5", 3, 4),
                trip(2, at(2, 8), TripStatus.IN_TRANSIT, 2L, null, "30", 1, 1)
        ), List.of());

        assertThat(r.getDelivery().getCleanItems()).isEqualTo(4);
        assertThat(r.getDelivery().getFinishedItems()).isEqualTo(5);
        assertThat(r.getDelivery().getCleanPercent()).isEqualByComparingTo("80.00");
        assertThat(r.getDeliveredWeightKg()).isEqualByComparingTo("150.5");
    }

    @Test
    void vehiclesAreCountedByStatusAtReadTime() {
        ManagerDashboardResponse r = ManagerDashboardCalculator.compute(FROM, TO, List.of(), List.of(
                VehicleStatus.AVAILABLE, VehicleStatus.AVAILABLE, VehicleStatus.IN_TRIP,
                VehicleStatus.MAINTENANCE, VehicleStatus.RETIRED));

        assertThat(r.getVehicles().getTotal()).isEqualTo(5);
        assertThat(r.getVehicles().getAvailable()).isEqualTo(2);
        assertThat(r.getVehicles().getInService()).isEqualTo(1);
        assertThat(r.getVehicles().getMaintenance()).isEqualTo(1);
        assertThat(r.getVehicles().getRetired()).isEqualTo(1);
    }

    @Test
    void weightByVehicleSumsDeliveredWeightHeaviestFirst() {
        ManagerDashboardResponse r = ManagerDashboardCalculator.compute(FROM, TO, List.of(
                trip(1, at(1, 8), TripStatus.DELIVERED, 1L, "0.5", "100", 0, 0),
                trip(2, at(2, 8), TripStatus.DELIVERED, 2L, "0.9", "300", 0, 0),
                trip(3, at(3, 8), TripStatus.DELIVERED, 1L, null, "50", 0, 0),
                trip(4, at(3, 9), TripStatus.DRAFT, null, null, "0", 0, 0)
        ), List.of());

        assertThat(r.getWeightByVehicle()).extracting(ManagerDashboardResponse.VehicleWeight::getVehicleId)
                .containsExactly(2L, 1L);
        ManagerDashboardResponse.VehicleWeight first = r.getWeightByVehicle().get(1);
        assertThat(first.getVehicleName()).isEqualTo("Xe 1");
        assertThat(first.getTripCount()).isEqualTo(2);
        assertThat(first.getDeliveredWeightKg()).isEqualByComparingTo("150");
        assertThat(first.getAverageFillPercent()).isEqualByComparingTo("50.00");
    }

    @Test
    void vehicleWithoutApprovedPlanHasNullFill() {
        ManagerDashboardResponse r = ManagerDashboardCalculator.compute(FROM, TO, List.of(
                trip(1, at(1, 8), TripStatus.DRAFT, 1L, null, "0", 0, 0)
        ), List.of());
        assertThat(r.getWeightByVehicle().get(0).getAverageFillPercent()).isNull();
    }

    @Test
    void recentTripsAreTheTenLatestDepartures() {
        List<TripFacts> trips = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            trips.add(trip(i, LocalDateTime.of(2026, 11, 1, 0, 0).plusHours(i), TripStatus.PLANNED,
                    1L, i == 12 ? "0.75" : null, "0", 0, 0));
        }
        ManagerDashboardResponse r = ManagerDashboardCalculator.compute(FROM, TO, trips, List.of());

        assertThat(r.getRecentTrips()).hasSize(10);
        assertThat(r.getRecentTrips()).extracting(ManagerDashboardResponse.RecentTrip::getTripId)
                .startsWith(12L, 11L).endsWith(3L);
        ManagerDashboardResponse.RecentTrip latest = r.getRecentTrips().get(0);
        assertThat(latest.getTripCode()).isEqualTo("TRIP-12");
        assertThat(latest.getStatus()).isEqualTo("PLANNED");
        assertThat(latest.getVehicleName()).isEqualTo("Xe 1");
        assertThat(latest.getDriverName()).isEqualTo("Tài xế 12");
        assertThat(latest.getFillPercent()).isEqualByComparingTo("75.00");
        assertThat(r.getRecentTrips().get(1).getFillPercent()).isNull();
    }
}
