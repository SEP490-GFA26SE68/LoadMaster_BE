package fu.se184491.loadmaster_be.service.cargo.Impl;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.dto.request.cargo.PackageRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.PackageResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackagePackingProfile;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackagePackingProfileRepository;
import fu.se184491.loadmaster_be.repository.cargo.PackageRepository;
import fu.se184491.loadmaster_be.repository.cargo.PackageTypeRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.order.OrderRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.service.cargo.PackageService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final EntityManager entityManager;
    private final CargoPackagePackingProfileRepository packingProfileRepository;
    private final DeliveryRequirementRepository deliveryRequirementRepository;

    // =====================================================
    // MAPPING
    // =====================================================


    private PackageResponse mapToResponse(CargoPackage entity) {

        CargoPackagePackingProfile profile =
                packingProfileRepository
                        .findByCargoPackageId(entity.getId())
                        .orElse(null);

        PackageResponse.PackageResponseBuilder builder =
                PackageResponse.builder()
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
                        .packageName(entity.getPackageName())
                        .trackingBarcode(entity.getTrackingBarcode())
                        .qrToken(entity.getQrToken())
                        .actualLength(entity.getActualLength())
                        .actualWidth(entity.getActualWidth())
                        .actualHeight(entity.getActualHeight())
                        .actualWeightKg(entity.getActualWeightKg())
                        .handlingClass(entity.getHandlingClass())
                        .destination(entity.getDestination())
                        .note(entity.getNote())
                        .status(entity.getStatus())
                        .isPinned(entity.getIsPinned());

        // Kiện cũ có thể chưa có packing profile.
        if (profile != null) {
            builder
                    .orientation(profile.getOrientation())
                    .uprightOnly(profile.getUprightOnly())
                    .fragilityLevel(profile.getFragilityLevel())
                    .stackable(profile.getStackable())
                    .maxTopLoadKg(profile.getMaxTopLoadKg())
                    .maxLayers(profile.getMaxLayers())
                    .minSupportRatio(profile.getMinSupportRatio())
                    .priority(profile.getPriority())
                    .mustLoad(profile.getMustLoad())
                    .packingGroup(profile.getPackingGroup());
        }

        return builder.build();
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

        // API hiện tại chỉ trả về một PackageResponse.
// Chưa hỗ trợ tạo nhiều kiện trong một request.
        if (request.getQuantity() == null
                || request.getQuantity() != 1) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }
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
                .packageName(request.getPackageName())
                .note(request.getNote())
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

        // QR token được sinh tại @PrePersist của CargoPackage.
        cargoPackage = packageRepository.save(cargoPackage);

        // Lưu thông số xếp hàng riêng của kiện.
        CargoPackagePackingProfile profile =
                CargoPackagePackingProfile.builder()
                        .cargoPackage(cargoPackage)
                        .orientation(request.getOrientation())
                        .uprightOnly(request.getUprightOnly())
                        .fragilityLevel(request.getFragilityLevel())
                        .stackable(request.getStackable())
                        .maxTopLoadKg(request.getMaxTopLoadKg())
                        .maxLayers(request.getMaxLayers())
                        .minSupportRatio(request.getMinSupportRatio())
                        .priority(request.getPriority())
                        .mustLoad(request.getMustLoad())
                        .packingGroup(request.getPackingGroup())
                        .build();

        entityManager.persist(profile);

        recalculateOrderTotalWeight(order);

        return mapToResponse(cargoPackage);

    }


    @Override
    @Transactional
    public List<PackageResponse> createPackages(
            Long companyId,
            PackageRequest request
    ) {
        Integer quantity = request.getQuantity();

        if (quantity == null || quantity < 1 || quantity > 1000) {
            throw new AppException(ErrorCode.IMPORT_INVALID_QUANTITY);
        }


        Long requirementId = request.getDeliveryRequirementId();

        if (requirementId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

// Khóa yêu cầu để tránh nhập kiện đồng thời với việc phân chuyến.
        DeliveryRequirement requirement = deliveryRequirementRepository
                .findByIdAndCompanyIdForUpdate(
                        requirementId,
                        companyId
                )
                .orElseThrow(() ->
                        new AppException(
                                ErrorCode.DELIVERY_REQUIREMENT_NOT_FOUND
                        )
                );

        if (requirement.getStatus() != DeliveryRequirementStatus.PENDING
                || requirement.getTrip() != null) {
            throw new AppException(
                    ErrorCode.INVALID_DELIVERY_REQUIREMENT_TRANSITION
            );
        }


        String baseCode = normalizeOptionalCode(request.getPackageCode());

        // Cần mã gốc để sinh các mã kiện vật lý.
        if (baseCode == null) {
            throw new AppException(ErrorCode.IMPORT_BLANK_PACKAGE_CODE);
        }

        Company company = companyRepository.findById(companyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.COMPANY_NOT_FOUND)
                );

        TransportOrder order = resolveOrder(
                companyId,
                request.getOrderId()
        );

        PackageType packageType = resolvePackageType(
                companyId,
                request.getPackageTypeId()
        );

        // Kiểm tra toàn bộ mã sẽ sinh trước khi bắt đầu lưu.
        Set<String> generatedCodes = new HashSet<>();

        for (int i = 1; i <= quantity; i++) {
            String code = buildManualPackageCode(baseCode, quantity, i);

            if (code.length() > 100) {
                throw new AppException(ErrorCode.INVALID_INPUT);
            }

            if (!generatedCodes.add(code.toUpperCase(Locale.ROOT))) {
                throw new AppException(
                        ErrorCode.IMPORT_DUPLICATE_GENERATED_CODE
                );
            }

            Long existingCount = entityManager.createQuery(
                            """
                            SELECT COUNT(p)
                            FROM CargoPackage p
                            WHERE p.company.id = :companyId
                              AND UPPER(p.packageCode) = :code
                            """,
                            Long.class
                    )
                    .setParameter("companyId", companyId)
                    .setParameter("code", code.toUpperCase(Locale.ROOT))
                    .getSingleResult();

            if (existingCount > 0) {
                throw new AppException(
                        ErrorCode.IMPORT_DUPLICATE_GENERATED_CODE
                );
            }
        }

        List<CargoPackage> createdPackages = new ArrayList<>();
        List<PackageResponse> results = new ArrayList<>();

        for (int i = 1; i <= quantity; i++) {
            String packageCode = buildManualPackageCode(
                    baseCode,
                    quantity,
                    i
            );

            CargoPackage cargoPackage = CargoPackage.builder()
                    .company(company)
                    .order(order)
                    .packageType(packageType)
                    .packageCode(packageCode)
                    .packageName(request.getPackageName())
                    .note(request.getNote())
                    .actualWeightKg(request.getActualWeightKg())
                    .handlingClass(request.getHandlingClass())
                    .destination(request.getDestination().trim())
                    .isPinned(Boolean.TRUE.equals(request.getIsPinned()))
                    .status(PackageStatus.PENDING)
                    .build();

            // Giữ quy tắc kích thước của API thủ công hiện tại:
            // chọn PackageType thì dùng kích thước của loại kiện.
            applyDimensions(cargoPackage, request, packageType);

            // QR riêng được tạo bởi @PrePersist của CargoPackage.
            CargoPackage saved = packageRepository.save(cargoPackage);
            createdPackages.add(saved);

            CargoPackagePackingProfile profile =
                    CargoPackagePackingProfile.builder()
                            .cargoPackage(saved)
                            .orientation(request.getOrientation())
                            .uprightOnly(request.getUprightOnly())
                            .fragilityLevel(request.getFragilityLevel())
                            .stackable(request.getStackable())
                            .maxTopLoadKg(request.getMaxTopLoadKg())
                            .maxLayers(request.getMaxLayers())
                            .minSupportRatio(request.getMinSupportRatio())
                            .priority(request.getPriority())
                            .mustLoad(request.getMustLoad())
                            .packingGroup(request.getPackingGroup())
                            .build();

            entityManager.persist(profile);

            results.add(mapToResponse(saved));
        }


// Liên kết toàn bộ kiện vật lý vừa tạo với yêu cầu giao.
        requirement.getPackages().addAll(createdPackages);

// Đồng bộ liên kết trong transaction hiện tại.
        deliveryRequirementRepository.save(requirement);

        // Nếu có TransportOrder, cập nhật tổng khối lượng
        // sau khi tạo đủ các kiện.
        recalculateOrderTotalWeight(order);

        return results;
    }

    private static String buildManualPackageCode(
            String baseCode,
            int quantity,
            int index
    ) {
        if (quantity == 1) {
            return baseCode;
        }

        return baseCode + "-" + String.format(
                Locale.ROOT,
                "%03d",
                index
        );
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

        if (request.getPackageName() != null) {
            cargoPackage.setPackageName(request.getPackageName().trim());
        }

        if (request.getNote() != null) {
            cargoPackage.setNote(request.getNote());
        }

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

        CargoPackagePackingProfile profile =
                packingProfileRepository
                        .findByCargoPackageId(cargoPackage.getId())
                        .orElse(null);

        if (profile == null) {
            profile = CargoPackagePackingProfile.builder()
                    .cargoPackage(cargoPackage)
                    .build();
        }

        if (request.getOrientation() != null) {
            profile.setOrientation(request.getOrientation());
        }

        if (request.getUprightOnly() != null) {
            profile.setUprightOnly(request.getUprightOnly());
        }

        if (request.getFragilityLevel() != null) {
            profile.setFragilityLevel(request.getFragilityLevel());
        }

        if (request.getStackable() != null) {
            profile.setStackable(request.getStackable());
        }

        if (request.getMaxTopLoadKg() != null) {
            profile.setMaxTopLoadKg(request.getMaxTopLoadKg());
        }

        if (request.getMaxLayers() != null) {
            profile.setMaxLayers(request.getMaxLayers());
        }

        if (request.getMinSupportRatio() != null) {
            profile.setMinSupportRatio(request.getMinSupportRatio());
        }

        if (request.getPriority() != null) {
            profile.setPriority(request.getPriority());
        }

        if (request.getMustLoad() != null) {
            profile.setMustLoad(request.getMustLoad());
        }

        if (request.getPackingGroup() != null) {
            profile.setPackingGroup(request.getPackingGroup());
        }

        packingProfileRepository.save(profile);


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

// Xóa packing profile trước để tránh vi phạm khóa ngoại.
        packingProfileRepository.deleteByCargoPackageId(
                cargoPackage.getId()
        );

// Đảm bảo lệnh DELETE profile được thực thi trước
// khi xóa CargoPackage.
        packingProfileRepository.flush();

// Xóa kiện vật lý.
        packageRepository.delete(cargoPackage);
        packageRepository.flush();

// Cập nhật lại tổng khối lượng đơn hàng nếu có.
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


        Page<CargoPackage> packagePage =
                packageRepository.findAll(spec, pageable);

        if (packagePage.isEmpty()) {
            return packagePage.map(this::mapToResponse);
        }

        List<Long> packageIds = packagePage.getContent()
                .stream()
                .map(CargoPackage::getId)
                .toList();

        Map<Long, DeliveryRequirementRepository.PackageRequirementInfo> infoByPackageId =
                deliveryRequirementRepository.findPackageRequirementInfo(
                                companyId,
                                packageIds
                        )
                        .stream()
                        .collect(Collectors.toMap(
                                DeliveryRequirementRepository.PackageRequirementInfo::getPackageId,
                                Function.identity()
                        ));

        return packagePage.map(pkg -> {
            PackageResponse response = mapToResponse(pkg);

            var info = infoByPackageId.get(pkg.getId());

            if (info != null) {
                response.setDeliveryRequirementId(
                        info.getDeliveryRequirementId()
                );
                response.setCustomerName(info.getCustomerName());
                response.setReceivedPackageCount(
                        Math.toIntExact(info.getReceivedPackageCount())
                );
                response.setExpectedPackageCount(
                        info.getExpectedPackageCount()
                );
            }

            return response;
        });

    }
}