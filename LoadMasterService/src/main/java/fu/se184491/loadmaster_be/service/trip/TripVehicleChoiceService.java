package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleChoicesResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleChoicesResponse.Cargo;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleChoicesResponse.Choice;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleChoicesResponse.Issue;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleConfigResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Xe để đổi cho một chuyến: mọi xe của công ty kèm lý do chọn được hay không, cùng luật với {@code PATCH /api/trips/{id}}
 * (xe đang dùng, đang bảo dưỡng / ngừng dùng, đang chạy chuyến khác, không chở được hàng). Xe chọn được đứng trước.
 */
@Service
@RequiredArgsConstructor
public class TripVehicleChoiceService {

    private static final List<TripStatus> BUSY = List.of(TripStatus.LOADING, TripStatus.IN_TRANSIT);

    private final TripRepository tripRepository;
    private final VehicleRepository vehicleRepository;
    private final TripPackageResolver tripPackageResolver;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public TripVehicleChoicesResponse choices(Long tripId) {
        Long companyId = currentUserService.getCurrentCompanyId();
        Trip trip = tripRepository.findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
        List<CargoPackage> packages = tripPackageResolver.packagesOfTrip(tripId);

        Map<Long, String> busyTrip = new HashMap<>();
        for (TripStatus status : BUSY) {
            for (Trip other : tripRepository.findByCompanyIdAndStatus(companyId, status)) {
                if (other.getVehicle() != null && !Objects.equals(other.getId(), tripId)) {
                    busyTrip.put(other.getVehicle().getId(), other.getTripCode());
                }
            }
        }
        Long currentId = trip.getVehicle() == null ? null : trip.getVehicle().getId();

        List<Choice> choices = new ArrayList<>();
        for (Vehicle vehicle : vehicleRepository.findByCompanyId(companyId, Pageable.unpaged()).getContent()) {
            if (vehicle.getVehicleType() == null) {
                continue;
            }
            boolean current = Objects.equals(vehicle.getId(), currentId);
            String busy = busyTrip.get(vehicle.getId());
            List<Issue> issues = VehicleCargoFit.issues(vehicle.getVehicleType(), packages).stream()
                    .map(issue -> new Issue(issue.code(), issue.severity(), issue.params()))
                    .toList();
            boolean selectable = !current && busy == null && vehicle.getStatus() == VehicleStatus.AVAILABLE
                    && issues.isEmpty();
            choices.add(new Choice(TripVehicleConfigResponse.of(vehicle),
                    vehicle.getStatus() == null ? null : vehicle.getStatus().name(),
                    current, busy, selectable, issues));
        }
        choices.sort(Comparator.comparing((Choice choice) -> !choice.selectable())
                .thenComparing(choice -> choice.vehicle().vehicleName(), Comparator.nullsLast(String::compareTo)));

        BigDecimal totalKg = packages.stream().map(CargoPackage::getActualWeightKg).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCm3 = packages.stream()
                .filter(p -> p.getActualLength() != null && p.getActualWidth() != null && p.getActualHeight() != null)
                .map(p -> p.getActualLength().multiply(p.getActualWidth()).multiply(p.getActualHeight()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TripVehicleChoicesResponse(new Cargo(packages.size(), totalKg, totalCm3), choices);
    }
}
