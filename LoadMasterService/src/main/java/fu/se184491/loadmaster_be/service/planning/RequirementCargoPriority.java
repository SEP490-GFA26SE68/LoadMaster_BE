package fu.se184491.loadmaster_be.service.planning;

import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackagePackingProfile;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackagePackingProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * D-93: ưu tiên yêu cầu giao → {@code priority} / {@code mustLoad} của packing profile khi kiện vào chuyến
 * và khi duyệt phương án tuyến (LIFO theo thứ tự điểm đã chốt; kiện Khẩn bắt buộc xếp lúc tối ưu 3D).
 */
@Service
@RequiredArgsConstructor
public class RequirementCargoPriority {

    private final CargoPackagePackingProfileRepository packingProfileRepository;

    @Transactional
    public void apply(DeliveryRequirement requirement) {
        if (requirement == null) {
            return;
        }
        apply(requirement.getPackages(), requirement.getPriority());
    }

    @Transactional
    public void apply(Iterable<CargoPackage> packages, DeliveryRequirementPriority priority) {
        if (packages == null) {
            return;
        }
        DeliveryRequirementPriority rank = priority == null ? DeliveryRequirementPriority.NORMAL : priority;
        for (CargoPackage cargo : packages) {
            apply(cargo, rank);
        }
    }

    @Transactional
    public void applyStops(List<DeliveryStop> stops) {
        if (stops == null) {
            return;
        }
        for (DeliveryStop stop : stops) {
            apply(stop.getDeliveryRequirement());
        }
    }

    private void apply(CargoPackage cargo, DeliveryRequirementPriority priority) {
        CargoPackagePackingProfile profile = packingProfileRepository
                .findByCargoPackageId(cargo.getId())
                .orElse(null);
        if (profile == null) {
            profile = CargoPackagePackingProfile.builder()
                    .cargoPackage(cargo)
                    .build();
        }
        profile.setPriority(priority.packingRank());
        profile.setMustLoad(priority.mustLoad());
        packingProfileRepository.save(profile);
    }
}
