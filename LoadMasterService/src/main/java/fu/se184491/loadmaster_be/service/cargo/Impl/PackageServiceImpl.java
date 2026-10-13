package fu.se184491.loadmaster_be.service.cargo.Impl;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.dto.request.cargo.PackageRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.PackageResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.PackageRepository;
import fu.se184491.loadmaster_be.repository.cargo.PackageTypeRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
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

// Import theo package thực tế của dự án:
// import ...Company;
// import ...CompanyRepository;

@Service
@RequiredArgsConstructor
public class PackageServiceImpl implements PackageService {

    private final PackageRepository packageRepository;
    private final PackageTypeRepository packageTypeRepository;
    private final OrderRepository orderRepository;
    private final CompanyRepository companyRepository;

    // =====================================================
    // MAPPING
    // =====================================================

    private PackageResponse mapToResponse(CargoPackage entity) {
        return PackageResponse.builder()
                .id(entity.getId())
                .orderId(
                        entity.getOrder() != null
                                ? entity.getOrder().getId()
                                : null
                )
                .packageTypeId(
                        entity.getPackageType() != null
                                ? entity.getPackageType().getId()
                                : null
                )
                .packageCode(entity.getPackageCode())
                .trackingBarcode(entity.getTrackingBarcode())
                .qrToken(entity.getQrToken())
                .actualLength(entity.getActualLength())
                .actualWidth(entity.getActualWidth())
                .actualHeight(entity.getActualHeight())
                .actualWeightKg(entity.getActualWeightKg())
                .handlingClass(entity.getHandlingClass())
                .destination(entity.getDestination())
                .status(entity.getStatus())
                .isPinned(entity.getIsPinned())
                .build();
    }

    // =====================================================
    // HELPER: TÌM KIỆN THUỘC CÔNG TY
    // =====================================================

    private CargoPackage getOwnedPackage(
            Long companyId,
            Long packageId
    ) {
        return packageRepository
                .findByIdAndCompany_Id(packageId, companyId)
                .orElseThrow(
                        () -> new AppException(
                                ErrorCode.PACKAGE_NOT_FOUND
                        )
                );
    }

    // =====================================================
    // HELPER: ĐƠN VẬN CHUYỂN (TÙY CHỌN)
    // =====================================================

    private TransportOrder resolveOrder(
            Long companyId,
            Long orderId
    ) {
        if (orderId == null) {
            return null;
        }

        return orderRepository.findById(orderId)
                .filter(order ->
                        Objects.equals(
                                order.getCompany().getId(),
                                companyId
                        )
                )
                .orElseThrow(
                        () -> new AppException(
                                ErrorCode.ORDER_NOT_FOUND
                        )
                );
    }

    // =====================================================
    // HELPER: LOẠI KIỆN (TÙY CHỌN)
    // =====================================================

    private PackageType resolvePackageType(
            Long companyId,
            Long packageTypeId
    ) {
        if (packageTypeId == null) {
            return null;
        }

        return packageTypeRepository
                .findById(packageTypeId)
                .filter(type ->
                        Objects.equals(
                                type.getCompany().getId(),
                                companyId
                        )
                )
                .orElseThrow(
                        () -> new AppException(
                                ErrorCode.PACKAGE_TYPE_NOT_FOUND
                        )
                );
    }

    // =====================================================
    // HELPER: ÁP DỤNG KÍCH THƯỚC
    // =====================================================

    private void applyDimensions(
            CargoPackage cargoPackage,
            PackageRequest request,
            PackageType packageType
    ) {
        // Có loại kiện: kích thước cố định theo PackageType.
        // Không sử dụng kích thước mà FE gửi lên.
        if (packageType != null) {
            cargoPackage.setActualLength(packageType.getLength());
            cargoPackage.setActualWidth(packageType.getWidth());
            cargoPackage.setActualHeight(packageType.getHeight());
            return;
        }

        // Không có loại kiện: bắt buộc nhập đủ 3 chiều.
        Integer length = request.getActualLength();
        Integer width = request.getActualWidth();
        Integer height = request.getActualHeight();

        if (length == null || width == null || height == null) {
            throw new IllegalArgumentException(
                    "Vui lòng nhập đủ chiều dài, chiều rộng " +
                            "và chiều cao khi không chọn loại kiện"
            );
        }

        if (length <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Kích thước kiện phải lớn hơn 0"
            );
        }

        cargoPackage.setActualLength(length);
        cargoPackage.setActualWidth(width);
        cargoPackage.setActualHeight(height);
    }

    // =====================================================
    // HELPER: CHUẨN HÓA MÃ
    // =====================================================

    private String normalizeOptionalCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }

        return code.trim();
    }

    // =====================================================
    // HELPER: TÍNH LẠI KHỐI LƯỢNG ĐƠN
    // =====================================================

    private void recalculateOrderTotalWeight(
            TransportOrder order
    ) {
        if (order == null || order.getId() == null) {
            return;
        }

        List<CargoPackage> packages =
                packageRepository.findByOrderId(order.getId());

        BigDecimal total = packages.stream()
                .map(CargoPackage::getActualWeightKg)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        order.setTotalWeightKg(total);
        orderRepository.save(order);
    }

    // =====================================================
    // CREATE
    // =====================================================

    @Override
    @Transactional
    public PackageResponse createPackage(
            Long companyId,
            PackageRequest request
    ) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(
                        () -> new AppException(
                                ErrorCode.INVALID_INPUT
                        )
                );

        TransportOrder order = resolveOrder(
                companyId,
                request.getOrderId()
        );

        PackageType packageType = resolvePackageType(
                companyId,
                request.getPackageTypeId()
        );

        CargoPackage cargoPackage = CargoPackage.builder()
                .company(company)
                .order(order)
                .packageType(packageType)
                .packageCode(
                        normalizeOptionalCode(request.getPackageCode())
                )
                .actualWeightKg(request.getActualWeightKg())
                .handlingClass(request.getHandlingClass())
                .destination(request.getDestination().trim())
                .isPinned(
                        Boolean.TRUE.equals(request.getIsPinned())
                )
                .status(PackageStatus.PENDING)
                .build();

        applyDimensions(
                cargoPackage,
                request,
                packageType
        );

        // qrToken được sinh ở @PrePersist của CargoPackage.
        // trackingBarcode tạm để null vì form mới không yêu cầu.
        cargoPackage = packageRepository.save(cargoPackage);

        recalculateOrderTotalWeight(order);

        return mapToResponse(cargoPackage);
    }

    // =====================================================
    // UPDATE
    // =====================================================

    @Override
    @Transactional
    public PackageResponse updatePackage(
            Long companyId,
            Long id,
            PackageRequest request
    ) {
        CargoPackage cargoPackage = getOwnedPackage(
                companyId,
                id
        );

        // Không cho sửa kiện đã xếp, đã giao hoặc đã hủy.
        if (cargoPackage.getStatus() == PackageStatus.LOADED
                || cargoPackage.getStatus() == PackageStatus.DELIVERED
                || cargoPackage.getStatus() == PackageStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Không thể chỉnh sửa kiện ở trạng thái hiện tại"
            );
        }

        TransportOrder oldOrder = cargoPackage.getOrder();

        TransportOrder newOrder = resolveOrder(
                companyId,
                request.getOrderId()
        );

        PackageType packageType = resolvePackageType(
                companyId,
                request.getPackageTypeId()
        );

        cargoPackage.setOrder(newOrder);
        cargoPackage.setPackageType(packageType);

        cargoPackage.setPackageCode(
                normalizeOptionalCode(request.getPackageCode())
        );

        cargoPackage.setActualWeightKg(
                request.getActualWeightKg()
        );

        cargoPackage.setHandlingClass(
                request.getHandlingClass()
        );

        cargoPackage.setDestination(
                request.getDestination().trim()
        );

        if (request.getIsPinned() != null) {
            cargoPackage.setIsPinned(
                    request.getIsPinned()
            );
        }

        applyDimensions(
                cargoPackage,
                request,
                packageType
        );

        cargoPackage = packageRepository.save(cargoPackage);

        // Đơn cũ và đơn mới giống nhau:
        // vẫn phải tính lại vì khối lượng kiện có thể thay đổi.
        recalculateOrderTotalWeight(newOrder);

        // Kiện được chuyển sang đơn khác hoặc gỡ khỏi đơn:
        // phải cập nhật khối lượng đơn cũ.
        if (oldOrder != null
                && (newOrder == null
                || !Objects.equals(
                oldOrder.getId(),
                newOrder.getId()
        ))) {
            recalculateOrderTotalWeight(oldOrder);
        }

        return mapToResponse(cargoPackage);
    }

    // =====================================================
    // DELETE
    // =====================================================

    @Override
    @Transactional
    public void deletePackage(
            Long companyId,
            Long id
    ) {
        CargoPackage cargoPackage = getOwnedPackage(
                companyId,
                id
        );

        if (cargoPackage.getStatus() == PackageStatus.LOADED
                || cargoPackage.getStatus() == PackageStatus.DELIVERED
                || cargoPackage.getStatus() == PackageStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Không thể xóa kiện ở trạng thái hiện tại"
            );
        }

        TransportOrder order = cargoPackage.getOrder();

        packageRepository.delete(cargoPackage);

        // Đẩy lệnh DELETE xuống DB trước khi truy vấn
        // tính lại tổng khối lượng.
        packageRepository.flush();

        recalculateOrderTotalWeight(order);
    }


    // =====================================================
    // GET BY ID
    // =====================================================

    @Override
    @Transactional(readOnly = true)
    public PackageResponse getPackageById(
            Long companyId,
            Long id
    ) {
        CargoPackage cargoPackage = getOwnedPackage(
                companyId,
                id
        );

        return mapToResponse(cargoPackage);
    }

    // =====================================================
    // GET ALL
    // =====================================================

    @Override
    @Transactional(readOnly = true)
    public Page<PackageResponse> getAllPackages(
            Long companyId,
            Long orderId,
            Long packageTypeId,
            Pageable pageable
    ) {
        Specification<CargoPackage> spec =
                (root, query, cb) -> {

                    List<Predicate> predicates =
                            new ArrayList<>();

                    // Luôn lọc theo công ty.
                    // Không phụ thuộc vào order vì order có thể null.
                    predicates.add(
                            cb.equal(
                                    root.get("company").get("id"),
                                    companyId
                            )
                    );

                    if (orderId != null) {
                        predicates.add(
                                cb.equal(
                                        root.get("order").get("id"),
                                        orderId
                                )
                        );
                    }

                    if (packageTypeId != null) {
                        predicates.add(
                                cb.equal(
                                        root.get("packageType").get("id"),
                                        packageTypeId
                                )
                        );
                    }

                    return cb.and(
                            predicates.toArray(new Predicate[0])
                    );
                };

        return packageRepository
                .findAll(spec, pageable)
                .map(this::mapToResponse);
    }
}