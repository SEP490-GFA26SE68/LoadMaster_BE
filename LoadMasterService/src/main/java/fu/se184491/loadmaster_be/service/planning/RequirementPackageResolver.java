package fu.se184491.loadmaster_be.service.planning;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Kiện điều phối viên chọn từ kho kiện cho một yêu cầu giao: kiện của công ty, đang chờ xử lý ở kho, không mang cờ và chưa thuộc yêu cầu
 * giao nào khác. Kiện đã thuộc chính yêu cầu đang sửa ({@code requirementId}) thì giữ được.
 */
@Component
@RequiredArgsConstructor
public class RequirementPackageResolver {

    private final CargoPackageRepository cargoPackageRepository;
    private final DeliveryRequirementRepository deliveryRequirementRepository;

    /** Kiện theo đúng thứ tự đã chọn (bỏ mã trùng); danh sách rỗng hoặc {@code null} là không kiện nào. */
    public Set<CargoPackage> resolve(Long companyId, Long requirementId, List<Long> packageIds) {
        if (packageIds == null || packageIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        List<Long> ids = packageIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, CargoPackage> byId = cargoPackageRepository.findAllById(ids).stream()
                .filter(pkg -> pkg.getCompany() != null && companyId.equals(pkg.getCompany().getId()))
                .collect(Collectors.toMap(CargoPackage::getId, Function.identity()));

        Set<CargoPackage> packages = new LinkedHashSet<>();
        for (Long id : ids) {
            CargoPackage pkg = byId.get(id);
            // Kiện của công ty khác đọc như không có.
            if (pkg == null) {
                throw new AppException(ErrorCode.PACKAGE_NOT_FOUND);
            }
            if (pkg.getFlag() != null) {
                throw new AppException(ErrorCode.PACKAGE_FLAGGED);
            }
            if (pkg.getStatus() != PackageStatus.PENDING) {
                throw new AppException(ErrorCode.PACKAGE_NOT_AVAILABLE);
            }
            packages.add(pkg);
        }

        boolean heldElsewhere = deliveryRequirementRepository.findByCompanyAndPackageIds(companyId, ids).stream()
                .anyMatch(requirement -> !Objects.equals(requirement.getId(), requirementId));
        if (heldElsewhere) {
            throw new AppException(ErrorCode.PACKAGE_IN_OTHER_REQUIREMENT);
        }
        return packages;
    }
}
