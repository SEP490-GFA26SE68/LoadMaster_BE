package fu.se184491.loadmaster_be.service.dashboard;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.dto.response.dashboard.ManagerDashboardResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManagerDashboardServiceTest {

    @Mock TripRepository tripRepository;
    @Mock LoadPlanRepository loadPlanRepository;
    @Mock DeliveryStopRepository deliveryStopRepository;
    @Mock VehicleRepository vehicleRepository;
    @Mock TripPackageResolver tripPackageResolver;

    @InjectMocks ManagerDashboardService service;

    private static final Long COMPANY = 1L;
    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 5);

    private static CargoPackage pkg(long id, PackageStatus status, String kg) {
        return CargoPackage.builder().id(id).status(status).actualWeightKg(new BigDecimal(kg)).build();
    }

    private Trip deliveredTrip() {
        Vehicle vehicle = Vehicle.builder().id(3L).name("Xe 29C").status(VehicleStatus.AVAILABLE).build();
        User driver = User.builder().id(8L).fullName("Trần Văn Tài").build();
        Trip trip = Trip.builder().id(10L).tripCode("TRIP-10").tripName("Biên Hoà")
                .departureTime(LocalDateTime.of(2026, 11, 2, 8, 30)).status(TripStatus.DELIVERED)
                .vehicle(vehicle).driver(driver).build();
        when(tripRepository.findDepartingBetween(eq(COMPANY), any(), any())).thenReturn(List.of(trip));
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L))
                .thenReturn(Optional.of(LoadPlan.builder().id(50L).volumeUtilization(new BigDecimal("0.8125")).build()));
        when(tripPackageResolver.packagesOfTrip(10L)).thenReturn(List.of(
                pkg(1, PackageStatus.DELIVERED, "100.50"),
                pkg(2, PackageStatus.DELIVERED, "20"),
                pkg(3, PackageStatus.LOADED, "999")));
        DeliveryStop done = DeliveryStop.builder().id(21L).status(DeliveryStopStatus.COMPLETED).build();
        DeliveryStop pending = DeliveryStop.builder().id(22L).status(DeliveryStopStatus.PENDING).build();
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(10L)).thenReturn(List.of(done, pending));
        when(tripPackageResolver.packagesOfStop(21L)).thenReturn(List.of(
                pkg(1, PackageStatus.DELIVERED, "100.50"), pkg(2, PackageStatus.DELIVERED, "20")));
        when(tripPackageResolver.packagesOfStop(22L)).thenReturn(List.of(pkg(3, PackageStatus.LOADED, "999")));
        when(vehicleRepository.findByCompanyId(eq(COMPANY), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(vehicle,
                        Vehicle.builder().id(4L).status(VehicleStatus.MAINTENANCE).build())));
        return trip;
    }

    @Test
    void readsOnlyTripsOfTheSessionCompanyDepartingInTheClosedPeriod() {
        deliveredTrip();

        service.get(COMPANY, FROM, TO);

        verify(tripRepository).findDepartingBetween(COMPANY,
                LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 11, 6, 0, 0));
        verify(vehicleRepository).findByCompanyId(eq(COMPANY), any(Pageable.class));
    }

    @Test
    void factsComeFromApprovedPlanDeliveredPackagesAndCompletedStops() {
        deliveredTrip();

        ManagerDashboardResponse r = service.get(COMPANY, FROM, TO);

        assertThat(r.getTripCount()).isEqualTo(1);
        assertThat(r.getFill().getAveragePercent()).isEqualByComparingTo("81.25");
        assertThat(r.getDeliveredWeightKg()).isEqualByComparingTo("120.50");
        assertThat(r.getDelivery().getFinishedItems()).isEqualTo(2);
        assertThat(r.getDelivery().getCleanItems()).isEqualTo(2);
        assertThat(r.getVehicles().getTotal()).isEqualTo(2);
        assertThat(r.getVehicles().getMaintenance()).isEqualTo(1);
        ManagerDashboardResponse.RecentTrip recent = r.getRecentTrips().get(0);
        assertThat(recent.getVehicleName()).isEqualTo("Xe 29C");
        assertThat(recent.getDriverName()).isEqualTo("Trần Văn Tài");
        assertThat(recent.getDepartureTime()).isEqualTo(OffsetDateTime.parse("2026-11-02T08:30:00+07:00"));
        assertThat(r.getWeightByVehicle().get(0).getDeliveredWeightKg()).isEqualByComparingTo("120.50");
    }

    @Test
    void tripWithoutApprovedPlanHasNoFill() {
        deliveredTrip();
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(anyLong())).thenReturn(Optional.empty());

        ManagerDashboardResponse r = service.get(COMPANY, FROM, TO);

        assertThat(r.getFill().getAveragePercent()).isNull();
        assertThat(r.getRecentTrips().get(0).getFillPercent()).isNull();
    }

    @Test
    void invalidPeriodIsRejectedWithoutReading() {
        assertThatThrownBy(() -> service.get(COMPANY, TO, FROM))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> service.get(COMPANY, FROM, FROM.plusDays(400)))
                .isInstanceOf(AppException.class);
        verify(tripRepository, never()).findDepartingBetween(any(), any(), any());
    }
}
