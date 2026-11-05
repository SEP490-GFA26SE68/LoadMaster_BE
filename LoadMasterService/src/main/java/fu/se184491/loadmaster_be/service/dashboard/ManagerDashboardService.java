package fu.se184491.loadmaster_be.service.dashboard;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.dto.response.dashboard.ManagerDashboardResponse;
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
import fu.se184491.loadmaster_be.service.dashboard.ManagerDashboardCalculator.TripFacts;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Đọc dữ kiện của các chuyến trong kỳ (chỉ công ty của phiên) rồi giao cho {@link ManagerDashboardCalculator}.
 */
@Service
@RequiredArgsConstructor
public class ManagerDashboardService {

    /** Kỳ dài nhất đọc được một lần. */
    static final long MAX_PERIOD_DAYS = 366;

    private final TripRepository tripRepository;
    private final LoadPlanRepository loadPlanRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final VehicleRepository vehicleRepository;
    private final TripPackageResolver tripPackageResolver;

    /** @param from, to ngày theo giờ Việt Nam, khoảng đóng. */
    @Transactional(readOnly = true)
    public ManagerDashboardResponse get(Long companyId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)
                || ChronoUnit.DAYS.between(from, to) >= MAX_PERIOD_DAYS) {
            throw new AppException(ErrorCode.INVALID_INPUT,
                    "Kỳ không hợp lệ: from phải trước hoặc bằng to, tối đa " + MAX_PERIOD_DAYS + " ngày");
        }
        // departure_time là giờ Việt Nam không kèm múi giờ.
        List<Trip> trips = tripRepository.findDepartingBetween(
                companyId, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        List<TripFacts> facts = trips.stream().map(this::factsOf).toList();
        List<VehicleStatus> vehicles = vehicleRepository.findByCompanyId(companyId, Pageable.unpaged())
                .getContent().stream()
                .map(Vehicle::getStatus)
                .toList();
        return ManagerDashboardCalculator.compute(from, to, facts, vehicles);
    }

    private TripFacts factsOf(Trip trip) {
        BigDecimal approvedFill = loadPlanRepository
                .findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(trip.getId())
                .map(LoadPlan::getVolumeUtilization)
                .orElse(null);

        BigDecimal deliveredWeight = tripPackageResolver.packagesOfTrip(trip.getId()).stream()
                .filter(pkg -> pkg.getStatus() == PackageStatus.DELIVERED)
                .map(CargoPackage::getActualWeightKg)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long clean = 0;
        long finished = 0;
        for (DeliveryStop stop : deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId())) {
            if (stop.getStatus() != DeliveryStopStatus.COMPLETED) {
                continue;
            }
            List<CargoPackage> packages = tripPackageResolver.packagesOfStop(stop.getId());
            finished += packages.size();
            clean += packages.stream().filter(pkg -> pkg.getStatus() == PackageStatus.DELIVERED).count();
        }

        Vehicle vehicle = trip.getVehicle();
        return new TripFacts(
                trip.getId(),
                trip.getTripCode(),
                trip.getTripName(),
                trip.getDepartureTime(),
                trip.getStatus(),
                vehicle == null ? null : vehicle.getId(),
                vehicle == null ? null : vehicle.getName(),
                trip.getDriver() == null ? null : trip.getDriver().getFullName(),
                approvedFill,
                deliveredWeight,
                clean,
                finished);
    }
}
