package fu.se184491.loadmaster_be.service.planning.Impl;

import fu.se184491.loadmaster_be.dto.request.planning.DeliveryRequirementRequest;
import fu.se184491.loadmaster_be.dto.request.planning.UpdateDeliveryRequirementRequest;
import fu.se184491.loadmaster_be.dto.response.planning.DeliveryRequirementResponse;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.service.planning.DeliveryRequirementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryRequirementServiceImpl implements DeliveryRequirementService {

    private final DeliveryRequirementRepository deliveryRequirementRepository;
    private final CargoPackageRepository cargoPackageRepository;

    @Override
    @Transactional
    public DeliveryRequirementResponse create(
            Long companyId,
            Long userId,
            DeliveryRequirementRequest request
    ) {
        List<Long> distinctPackageIds = request.getPackageIds().stream().distinct().toList();
        List<CargoPackage> packages = cargoPackageRepository
                .findAllByIdInAndOrderCompanyId(distinctPackageIds, companyId);
        if (packages.size() != distinctPackageIds.size()) {
            throw new AppException(ErrorCode.PACKAGE_NOT_FOUND);
        }
        if (packages.stream().map(CargoPackage::getHandlingClass).distinct().count() > 1) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        DeliveryRequirement requirement = DeliveryRequirement.builder()
                .company(Company.builder().id(companyId).build())
                .destination(request.getDestination().trim())
                .destinationLat(request.getDestinationLat())
                .destinationLng(request.getDestinationLng())
                .deadline(request.getDeadline())
                .priority(request.getPriority())
                .createdBy(User.builder().id(userId).build())
                .packages(new LinkedHashSet<>(packages))
                .build();

        return toResponse(deliveryRequirementRepository.save(requirement));
    }

    @Override
    public DeliveryRequirementResponse getById(Long companyId, Long id) {
        DeliveryRequirement requirement = deliveryRequirementRepository
                .findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.DELIVERY_REQUIREMENT_NOT_FOUND));
        return toResponse(requirement);
    }

    @Override
    public Page<DeliveryRequirementResponse> list(
            Long companyId,
            DeliveryRequirementStatus status,
            LocalDateTime deadlineFrom,
            LocalDateTime deadlineTo,
            Pageable pageable
    ) {
        Specification<DeliveryRequirement> filters = (root, query, builder) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(builder.equal(root.get("company").get("id"), companyId));
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (deadlineFrom != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("deadline"), deadlineFrom));
            }
            if (deadlineTo != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("deadline"), deadlineTo));
            }
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return deliveryRequirementRepository.findAll(filters, pageable).map(this::toResponse);
    }

    @Override
    @Transactional
    public DeliveryRequirementResponse update(
            Long companyId,
            Long id,
            UpdateDeliveryRequirementRequest request
    ) {
        DeliveryRequirement requirement = deliveryRequirementRepository
                .findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.DELIVERY_REQUIREMENT_NOT_FOUND));
        if (request.getDeadline() != null) {
            requirement.setDeadline(request.getDeadline());
        }
        if (request.getPriority() != null) {
            requirement.setPriority(request.getPriority());
        }
        return toResponse(requirement);
    }

    @Override
    @Transactional
    public void delete(Long companyId, Long id) {
        DeliveryRequirement requirement = deliveryRequirementRepository
                .findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.DELIVERY_REQUIREMENT_NOT_FOUND));
        if (requirement.getStatus() != DeliveryRequirementStatus.PENDING) {
            throw new AppException(ErrorCode.DELIVERY_REQUIREMENT_NOT_PENDING);
        }
        deliveryRequirementRepository.delete(requirement);
    }

    @Override
    @Transactional
    public DeliveryRequirementResponse markAssigned(Long companyId, Long id) {
        DeliveryRequirement requirement = findByTenant(companyId, id);
        requireStatus(requirement, DeliveryRequirementStatus.PENDING);
        requirement.setStatus(DeliveryRequirementStatus.ASSIGNED);
        return toResponse(requirement);
    }

    @Override
    @Transactional
    public DeliveryRequirementResponse markInTrip(Long companyId, Long id) {
        DeliveryRequirement requirement = findByTenant(companyId, id);
        requireStatus(requirement, DeliveryRequirementStatus.ASSIGNED);
        requirement.setStatus(DeliveryRequirementStatus.IN_TRIP);
        return toResponse(requirement);
    }

    private DeliveryRequirement findByTenant(Long companyId, Long id) {
        return deliveryRequirementRepository.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.DELIVERY_REQUIREMENT_NOT_FOUND));
    }

    private void requireStatus(
            DeliveryRequirement requirement,
            DeliveryRequirementStatus expectedStatus
    ) {
        if (requirement.getStatus() != expectedStatus) {
            throw new AppException(ErrorCode.INVALID_DELIVERY_REQUIREMENT_TRANSITION);
        }
    }

    private DeliveryRequirementResponse toResponse(DeliveryRequirement requirement) {
        return DeliveryRequirementResponse.builder()
                .id(requirement.getId())
                .companyId(requirement.getCompany().getId())
                .destination(requirement.getDestination())
                .destinationLat(requirement.getDestinationLat())
                .destinationLng(requirement.getDestinationLng())
                .deadline(requirement.getDeadline())
                .priority(requirement.getPriority())
                .status(requirement.getStatus())
                .createdBy(requirement.getCreatedBy() == null ? null : requirement.getCreatedBy().getId())
                .createdAt(requirement.getCreatedAt())
                .packageIds(requirement.getPackages().stream()
                        .map(CargoPackage::getId)
                        .sorted()
                        .toList())
                .build();
    }
}
