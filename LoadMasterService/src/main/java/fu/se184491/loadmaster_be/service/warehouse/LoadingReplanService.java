package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.cargo.PackageFlag;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.warehouse.ExecutionAbortReason;
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
import fu.se184491.loadmaster_be.service.trip.TripLifecycleService;
import fu.se184491.loadmaster_be.service.trip.TripStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
    private final TripLifecycleService tripLifecycleService;

    /**
     * Kiện rời chuyến (rời yêu cầu giao của chuyến), về kho kiện mang cờ {@code flag} và nhớ yêu cầu đã giữ nó (yêu cầu đó đọc là giao
     * thiếu). Phương án đang xếp không đổi. Trả các yêu cầu giao vừa hết kiện.
     */
    public List<DeliveryRequirement> leaveOut(Trip trip, CargoPackage pkg, PackageFlag flag) {
        List<DeliveryRequirement> emptied = new ArrayList<>();
        for (DeliveryRequirement requirement : deliveryRequirementRepository.findByTripId(trip.getId())) {
            if (requirement.getPackages().removeIf(entry -> pkg.getId().equals(entry.getId()))) {
                deliveryRequirementRepository.save(requirement);
                pkg.setDroppedRequirementId(requirement.getId());
                if (requirement.getPackages().isEmpty()) {
                    emptied.add(requirement);
                }
            }
        }
        pkg.setStatus(PackageStatus.PENDING);
        pkg.setFlag(flag);
        cargoPackageRepository.save(pkg);
        return emptied;
    }

    /**
     * Kiện rời chuyến mang cờ {@code flag} và chuyến Đang xếp hàng quay về Đã lập kế hoạch, chờ điều phối viên tối ưu lại. Yêu cầu giao vừa
     * hết kiện rời chuyến luôn (điểm giao của nó bị xoá, chuyến về Nháp) để lần tối ưu sau không nhận điểm giao không có kiện.
     */
    public void returnToPlanning(Trip trip, LoadingExecution execution, CargoPackage pkg, PackageFlag flag, User actor) {
        List<DeliveryRequirement> emptied = pkg != null ? leaveOut(trip, pkg, flag) : List.of();
        execution.setStatus(LoadingExecutionStatus.ABORTED);
        execution.setAbortReason(flag == PackageFlag.NOT_FOUND
                ? ExecutionAbortReason.STAGING_SHORTAGE : ExecutionAbortReason.DAMAGED_PACKAGE);
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
        for (DeliveryRequirement requirement : emptied) {
            tripLifecycleService.releaseRequirement(trip, requirement, actor, "NO_PACKAGES_LEFT");
        }
    }
}
