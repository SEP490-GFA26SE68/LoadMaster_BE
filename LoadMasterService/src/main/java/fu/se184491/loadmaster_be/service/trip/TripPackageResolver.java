package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nối kiện với điểm giao và chuyến.
 *
 * <p>Dữ liệu thật nối kiện → yêu cầu giao ({@code delivery_requirement_packages}) → điểm giao
 * ({@code delivery_stops.delivery_requirement_id}). Đường cũ kiện → đơn vận chuyển → điểm giao
 * vẫn được đọc thêm cho dữ liệu cũ; kiện xuất hiện ở cả hai đường chỉ tính một lần.</p>
 */
@Component
@RequiredArgsConstructor
public class TripPackageResolver {

    private final CargoPackageRepository cargoPackageRepository;
    private final DeliveryStopRepository deliveryStopRepository;

    public List<CargoPackage> packagesOfStop(Long stopId) {
        return merge(
                cargoPackageRepository.findByRequirementStopId(stopId),
                cargoPackageRepository.findByOrderDeliveryStopId(stopId)
        );
    }

    public List<CargoPackage> packagesOfTrip(Long tripId) {
        return merge(
                cargoPackageRepository.findByRequirementTripId(tripId),
                cargoPackageRepository.findByOrderDeliveryStopTripIdOrderByIdAsc(tripId)
        );
    }

    /**
     * Điểm giao của kiện. Ưu tiên điểm thuộc chuyến còn hiệu lực (chưa huỷ, chưa giao xong).
     */
    public Optional<DeliveryStop> stopOfPackage(CargoPackage cargoPackage) {
        List<DeliveryStop> stops = deliveryStopRepository.findRequirementStopsByPackageId(cargoPackage.getId());
        Optional<DeliveryStop> active = stops.stream()
                .filter(stop -> stop.getTrip() != null && isActive(stop.getTrip().getStatus()))
                .findFirst();
        if (active.isPresent()) {
            return active;
        }
        if (!stops.isEmpty()) {
            return Optional.of(stops.get(0));
        }
        if (cargoPackage.getOrder() != null && cargoPackage.getOrder().getDeliveryStop() != null) {
            return Optional.of(cargoPackage.getOrder().getDeliveryStop());
        }
        return Optional.empty();
    }

    private static boolean isActive(TripStatus status) {
        return status != TripStatus.CANCELLED && status != TripStatus.DELIVERED;
    }

    private static List<CargoPackage> merge(List<CargoPackage> first, List<CargoPackage> second) {
        Map<Long, CargoPackage> byId = new LinkedHashMap<>();
        for (CargoPackage cargoPackage : first) {
            byId.putIfAbsent(cargoPackage.getId(), cargoPackage);
        }
        for (CargoPackage cargoPackage : second) {
            byId.putIfAbsent(cargoPackage.getId(), cargoPackage);
        }
        return new ArrayList<>(byId.values());
    }
}
