package fu.se184491.loadmaster_be.service.cargo.Impl;

import fu.se184491.loadmaster_be.dto.request.cargo.PackageRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.PackageResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.PackageRepository;
import fu.se184491.loadmaster_be.repository.cargo.PackageTypeRepository;
import fu.se184491.loadmaster_be.repository.order.OrderRepository;
import fu.se184491.loadmaster_be.service.cargo.PackageService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PackageServiceImpl implements PackageService {

    private final PackageRepository packageRepository;
    private final OrderRepository orderRepository;
    private final PackageTypeRepository packageTypeRepository;

    private PackageResponse mapToResponse(CargoPackage entity) {
        return PackageResponse.builder()
                .id(entity.getId())
                .orderId(entity.getOrder() != null ? entity.getOrder().getId() : null)
                .packageTypeId(entity.getPackageType() != null ? entity.getPackageType().getId() : null)
                .trackingBarcode(entity.getTrackingBarcode())
                .actualLength(entity.getActualLength())
                .actualWeightKg(entity.getActualWeightKg())
                .isPinned(entity.getIsPinned())
                .packageCode(entity.getPackageCode())
                .qrToken(entity.getQrToken())
                .handlingClass(entity.getHandlingClass())
                .status(entity.getStatus())
                .build();
    }

    private void recalculateOrderTotalWeight(TransportOrder order) {
        if (order == null || order.getId() == null) {
            return;
        }
        List<CargoPackage> packages = packageRepository.findByOrderId(order.getId());
        BigDecimal total = packages.stream()
                .map(CargoPackage::getActualWeightKg)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalWeightKg(total);
        orderRepository.save(order);
    }

    @Override
    @Transactional
    public PackageResponse createPackage(Long companyId, PackageRequest request) {
        TransportOrder order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        PackageType packageType = packageTypeRepository.findById(request.getPackageTypeId())
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_TYPE_NOT_FOUND));

        if (!packageType.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        if (packageRepository.existsByTrackingBarcode(request.getTrackingBarcode())) {
            throw new AppException(ErrorCode.TRACKING_BARCODE_ALREADY_EXISTS);
        }

        CargoPackage cargoPackage = CargoPackage.builder()
                .order(order)
                .packageType(packageType)
                .trackingBarcode(request.getTrackingBarcode())
                .actualLength(request.getActualLength())
                .actualWeightKg(request.getActualWeightKg())
                .isPinned(request.getIsPinned() != null ? request.getIsPinned() : false)
                .build();

        cargoPackage = packageRepository.save(cargoPackage);
        recalculateOrderTotalWeight(order);

        return mapToResponse(cargoPackage);
    }

    @Override
    @Transactional
    public PackageResponse updatePackage(Long companyId, Long id, PackageRequest request) {
        CargoPackage cargoPackage = packageRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_NOT_FOUND));

        if (cargoPackage.getOrder() != null && !cargoPackage.getOrder().getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        TransportOrder order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        PackageType packageType = packageTypeRepository.findById(request.getPackageTypeId())
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_TYPE_NOT_FOUND));

        if (!packageType.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        if (packageRepository.existsByTrackingBarcodeAndIdNot(request.getTrackingBarcode(), id)) {
            throw new AppException(ErrorCode.TRACKING_BARCODE_ALREADY_EXISTS);
        }

        TransportOrder oldOrder = cargoPackage.getOrder();

        cargoPackage.setOrder(order);
        cargoPackage.setPackageType(packageType);
        cargoPackage.setTrackingBarcode(request.getTrackingBarcode());
        cargoPackage.setActualLength(request.getActualLength());
        cargoPackage.setActualWeightKg(request.getActualWeightKg());
        if (request.getIsPinned() != null) {
            cargoPackage.setIsPinned(request.getIsPinned());
        }

        cargoPackage = packageRepository.save(cargoPackage);

        recalculateOrderTotalWeight(order);
        if (oldOrder != null && !oldOrder.getId().equals(order.getId())) {
            recalculateOrderTotalWeight(oldOrder);
        }

        return mapToResponse(cargoPackage);
    }

    @Override
    @Transactional
    public void deletePackage(Long companyId, Long id) {
        CargoPackage cargoPackage = packageRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_NOT_FOUND));

        if (cargoPackage.getOrder() != null && !cargoPackage.getOrder().getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        TransportOrder order = cargoPackage.getOrder();

        packageRepository.delete(cargoPackage);

        recalculateOrderTotalWeight(order);
    }

    @Override
    public PackageResponse getPackageById(Long companyId, Long id) {
        CargoPackage cargoPackage = packageRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_NOT_FOUND));

        if (cargoPackage.getOrder() != null && !cargoPackage.getOrder().getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        return mapToResponse(cargoPackage);
    }

    @Override
    public Page<PackageResponse> getAllPackages(Long companyId, Long orderId, Long packageTypeId, Pageable pageable) {
        Specification<CargoPackage> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("order").get("company").get("id"), companyId));
            if (orderId != null) {
                predicates.add(cb.equal(root.get("order").get("id"), orderId));
            }
            if (packageTypeId != null) {
                predicates.add(cb.equal(root.get("packageType").get("id"), packageTypeId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return packageRepository.findAll(spec, pageable).map(this::mapToResponse);
    }
}
