package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.pickup.PickupRequestStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequestPackage;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.pickup.PickupRequestPackageRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Nối kiện với điểm giao và chuyến.
 *
 * <p>Dữ liệu thật nối kiện → yêu cầu giao ({@code delivery_requirement_packages}) → điểm giao
 * ({@code delivery_stops.delivery_requirement_id}). Đường cũ kiện → đơn vận chuyển → điểm giao
 * vẫn được đọc thêm cho dữ liệu cũ; kiện xuất hiện ở cả hai đường chỉ tính một lần. Kiện nhận dọc
 * đường thuộc điểm giao của yêu cầu nhận từ lúc đã lên xe ({@code LOADED}).</p>
 */
@Component
@RequiredArgsConstructor
public class TripPackageResolver {

    /** Yêu cầu nhận mà kiện đã lên xe: kiện là việc của điểm giao. */
    private static final Set<PickupRequestStatus> PICKUP_ON_BOARD =
            EnumSet.of(PickupRequestStatus.LOADED, PickupRequestStatus.DELIVERED);

    private final CargoPackageRepository cargoPackageRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final PickupRequestPackageRepository pickupRequestPackageRepository;

    public List<CargoPackage> packagesOfStop(Long stopId) {
        List<CargoPackage> pickups = pickupRequestPackageRepository.findDeliveriesAtStop(stopId, PICKUP_ON_BOARD)
                .stream().map(PickupRequestPackage::getCargoPackage).toList();
        return merge(
                cargoPackageRepository.findByRequirementStopId(stopId),
                cargoPackageRepository.findByOrderDeliveryStopId(stopId),
                pickups
        );
    }

    /** Kiện của chuyến theo yêu cầu giao (không gồm kiện nhận dọc đường: kho không xếp chúng). */
    public List<CargoPackage> packagesOfTrip(Long tripId) {
        return merge(
                cargoPackageRepository.findByRequirementTripId(tripId),
                cargoPackageRepository.findByOrderDeliveryStopTripIdOrderByIdAsc(tripId),
                List.of()
        );
    }

    /**
     * Điểm giao của kiện. Kiện nhận dọc đường: điểm giao của yêu cầu nhận. Kiện khác: ưu tiên điểm thuộc chuyến còn hiệu lực (chưa
     * huỷ, chưa giao xong).
     */
    public Optional<DeliveryStop> stopOfPackage(CargoPackage cargoPackage) {
        Optional<PickupRequestPackage> pickup = cargoPackage.getId() == null ? Optional.empty()
                : pickupRequestPackageRepository.findByCargoPackageId(cargoPackage.getId());
        if (pickup.isPresent() && pickup.get().getPickupRequest() != null
                && pickup.get().getPickupRequest().getDeliveryStop() != null) {
            return Optional.of(pickup.get().getPickupRequest().getDeliveryStop());
        }
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

    @SafeVarargs
    private static List<CargoPackage> merge(List<CargoPackage>... lists) {
        Map<Long, CargoPackage> byId = new LinkedHashMap<>();
        for (List<CargoPackage> list : lists) {
            for (CargoPackage cargoPackage : list) {
                byId.putIfAbsent(cargoPackage.getId(), cargoPackage);
            }
        }
        return new ArrayList<>(byId.values());
    }
}
