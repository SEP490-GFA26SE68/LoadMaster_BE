package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import fu.se184491.loadmaster_be.service.trip.TripStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Bỏ một kiện khỏi chuyến khi kho không xếp được nó: kiện rời yêu cầu giao của chuyến và về kho kiện ({@code PENDING}). Khi phương án không
 * còn dùng được (kiện thiếu bị bỏ, kiện hỏng có kiện tựa lên), chuyến quay về Đã lập kế hoạch: phiên xếp dừng, mọi phương án lỗi thời, kiện
 * đã lên xe về chờ xếp lại. Kiện đã soạn giữ lần soạn (theo chuyến), nên bắt đầu lại vẫn tính là đã soạn.
 */
@Component
@RequiredArgsConstructor
public class LoadingReplanService {

    private final DeliveryRequirementRepository deliveryRequirementRepository;
    private final CargoPackageRepository cargoPackageRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final LoadPlanRepository loadPlanRepository;
    private final ManualUnloadConfirmationRepository manualConfirmationRepository;
    private final TripPackageResolver tripPackageResolver;
    private final TripStatusService tripStatusService;

    /** Kiện rời chuyến (rời yêu cầu giao của chuyến) và về kho kiện. Phương án đang xếp không đổi. */
    public void leaveOut(Trip trip, CargoPackage pkg) {
        for (DeliveryRequirement requirement : deliveryRequirementRepository.findByTripId(trip.getId())) {
            if (requirement.getPackages().removeIf(entry -> pkg.getId().equals(entry.getId()))) {
                deliveryRequirementRepository.save(requirement);
            }
        }
        pkg.setStatus(PackageStatus.PENDING);
        cargoPackageRepository.save(pkg);
    }

    /** Kiện rời chuyến (khi có) và chuyến Đang xếp hàng quay về Đã lập kế hoạch, chờ điều phối viên tối ưu lại. */
    public void returnToPlanning(Trip trip, LoadingExecution execution, CargoPackage pkg, User actor) {
        if (pkg != null) {
            leaveOut(trip, pkg);
        }
        execution.setStatus(LoadingExecutionStatus.ABORTED);
        loadingExecutionRepository.save(execution);
        for (ManualUnloadConfirmation entry : manualConfirmationRepository.findByTripIdOrderByRequestedAtAsc(trip.getId())) {
            if (!entry.isUnloadingStep() && entry.getStatus() == ManualConfirmStatus.PENDING) {
                manualConfirmationRepository.delete(entry);
            }
        }
        for (CargoPackage other : tripPackageResolver.packagesOfTrip(trip.getId())) {
            if (other.getStatus() == PackageStatus.LOADED) {
                other.setStatus(PackageStatus.PLANNED);
                cargoPackageRepository.save(other);
            }
        }
        loadPlanRepository.markStaleByTripId(trip.getId());
        tripStatusService.transitionTo(trip.getId(), TripStatus.PLANNED, actor != null ? actor.getId() : null);
    }
}
