package fu.se184491.loadmaster_be.service.planning.Impl;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.planning.DeliveryRequirementRequest;
import fu.se184491.loadmaster_be.dto.request.planning.ExtendDeliveryDeadlineRequest;
import fu.se184491.loadmaster_be.dto.request.planning.UpdateDeliveryRequirementRequest;
import fu.se184491.loadmaster_be.dto.request.planning.UpdateRequirementCoordinatesRequest;
import fu.se184491.loadmaster_be.dto.response.planning.DeliveryRequirementResponse;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.entity.Customer;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.customer.CustomerRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.service.planning.DeliveryRequirementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryRequirementServiceImpl implements DeliveryRequirementService {

    private final DeliveryRequirementRepository deliveryRequirementRepository;
    private final CustomerRepository customerRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public DeliveryRequirementResponse create(
            Long companyId,
            Long userId,
            DeliveryRequirementRequest request
    ) {
        Customer customer = customerRepository
                .findById(request.getCustomerId())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_INPUT));

        // Không cho phép lấy khách hàng thuộc công ty logistics khác.
        if (customer.getCompany() == null
                || !companyId.equals(customer.getCompany().getId())) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        DeliveryRequirement requirement = DeliveryRequirement.builder()
                .company(Company.builder().id(companyId).build())
                .customer(customer)
                .destination(request.getDestination().trim())
                .destinationLat(request.getDestinationLat())
                .destinationLng(request.getDestinationLng())
                .deadline(request.getDeadline())
                .priority(request.getPriority())
                .expectedPackageCount(request.getExpectedPackageCount())
                .note(request.getNote() == null ? null : request.getNote().trim())
                .status(DeliveryRequirementStatus.PENDING)
                .createdBy(User.builder().id(userId).build())
                .packages(new LinkedHashSet<>())
                .build();

        DeliveryRequirement saved =
                deliveryRequirementRepository.save(requirement);

        return toResponse(saved);
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
                .orElseThrow(() -> new AppException(
                        ErrorCode.DELIVERY_REQUIREMENT_NOT_FOUND
                ));

        // Chỉ cho chỉnh sửa khi yêu cầu chưa được đưa vào chuyến.

        boolean assignedToTrip = requirement.getTrip() != null;

        if (!assignedToTrip
                && requirement.getStatus() != DeliveryRequirementStatus.PENDING) {
            throw new AppException(
                    ErrorCode.INVALID_DELIVERY_REQUIREMENT_TRANSITION
            );
        }

        if (assignedToTrip) {
            Trip trip = requirement.getTrip();

            if (trip.getStatus() != TripStatus.DRAFT
                    && trip.getStatus() != TripStatus.PLANNED) {
                throw new AppException(
                        ErrorCode.INVALID_DELIVERY_REQUIREMENT_TRANSITION
                );
            }

            // Khi đã vào Trip, request chỉ được chứa
            // deadline và priority.
            if (request.getCustomerId() != null
                    || request.getDestination() != null
                    || request.getDestinationLat() != null
                    || request.getDestinationLng() != null
                    || request.getExpectedPackageCount() != null
                    || request.getNote() != null) {
                throw new AppException(ErrorCode.INVALID_INPUT);
            }

            if (request.getDeadline() != null) {
                requirement.setDeadline(request.getDeadline());
            }

            if (request.getPriority() != null) {
                requirement.setPriority(request.getPriority());
            }

            return toResponse(requirement);
        }


        boolean hasPackages = !requirement.getPackages().isEmpty();

        // Khi đã nhận kiện, không cho đổi chủ hàng hoặc điểm giao.
        if (hasPackages) {
            if (request.getCustomerId() != null
                    && !request.getCustomerId().equals(
                    requirement.getCustomer() == null
                            ? null
                            : requirement.getCustomer().getId()
            )) {
                throw new AppException(ErrorCode.INVALID_INPUT);
            }

            if (request.getDestination() != null
                    && !request.getDestination().trim()
                    .equals(requirement.getDestination())) {
                throw new AppException(ErrorCode.INVALID_INPUT);
            }

            if (request.getDestinationLat() != null
                    && (requirement.getDestinationLat() == null
                    || request.getDestinationLat().compareTo(
                    requirement.getDestinationLat()) != 0)) {
                throw new AppException(ErrorCode.INVALID_INPUT);
            }

            if (request.getDestinationLng() != null
                    && (requirement.getDestinationLng() == null
                    || request.getDestinationLng().compareTo(
                    requirement.getDestinationLng()) != 0)) {
                throw new AppException(ErrorCode.INVALID_INPUT);
            }
        } else {
            // Chưa có kiện: cho phép đổi khách hàng.
            if (request.getCustomerId() != null) {
                Customer customer = customerRepository
                        .findById(request.getCustomerId())
                        .orElseThrow(() -> new AppException(
                                ErrorCode.INVALID_INPUT
                        ));

                if (customer.getCompany() == null
                        || !companyId.equals(customer.getCompany().getId())) {
                    throw new AppException(ErrorCode.INVALID_INPUT);
                }

                requirement.setCustomer(customer);
            }

            // Chưa có kiện: cho phép đổi điểm giao.
            if (request.getDestination() != null) {
                String destination = request.getDestination().trim();

                if (destination.isEmpty()) {
                    throw new AppException(ErrorCode.INVALID_INPUT);
                }

                requirement.setDestination(destination);
            }

            if (request.getDestinationLat() != null) {
                requirement.setDestinationLat(request.getDestinationLat());
            }

            if (request.getDestinationLng() != null) {
                requirement.setDestinationLng(request.getDestinationLng());
            }
        }

        // Các trường được cập nhật khi yêu cầu còn PENDING.
        if (request.getDeadline() != null) {
            requirement.setDeadline(request.getDeadline());
        }

        if (request.getPriority() != null) {
            requirement.setPriority(request.getPriority());
        }

        if (request.getExpectedPackageCount() != null) {
            requirement.setExpectedPackageCount(
                    request.getExpectedPackageCount()
            );
        }

        if (request.getNote() != null) {
            requirement.setNote(request.getNote().trim());
        }

        return toResponse(requirement);
    }


    @Override
    @Transactional
    public void delete(Long companyId, Long id) {
        DeliveryRequirement requirement = deliveryRequirementRepository
                .findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new AppException(
                        ErrorCode.DELIVERY_REQUIREMENT_NOT_FOUND
                ));

        if (requirement.getStatus() != DeliveryRequirementStatus.PENDING
                || requirement.getTrip() != null
                || !requirement.getPackages().isEmpty()) {
            throw new AppException(
                    ErrorCode.DELIVERY_REQUIREMENT_NOT_PENDING
            );
        }

        deliveryRequirementRepository.delete(requirement);
    }


    @Override
    @Transactional
    public DeliveryRequirementResponse updateCoordinates(
            Long companyId,
            Long id,
            UpdateRequirementCoordinatesRequest request
    ) {
        DeliveryRequirement requirement =
                deliveryRequirementRepository
                        .findByIdAndCompanyId(id, companyId)
                        .orElseThrow(() -> new AppException(
                                ErrorCode.DELIVERY_REQUIREMENT_NOT_FOUND
                        ));

        // Chỉ được bổ sung tọa độ còn thiếu,
        // không thay đổi vị trí đã xác nhận.
        if (requirement.getDestinationLat() != null
                || requirement.getDestinationLng() != null) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        Trip trip = requirement.getTrip();

        if (trip != null) {
            // Không thay đổi điểm giao khi chuyến đã bắt đầu xếp hàng.
            if (trip.getStatus() != TripStatus.DRAFT
                    && trip.getStatus() != TripStatus.PLANNED) {
                throw new AppException(
                        ErrorCode.INVALID_DELIVERY_REQUIREMENT_TRANSITION
                );
            }

            List<DeliveryStop> stops =
                    deliveryStopRepository
                            .findByTripIdAndDeliveryRequirementId(
                                    trip.getId(),
                                    requirement.getId()
                            );

            // Nếu yêu cầu đã vào chuyến nhưng không tìm được
            // điểm giao, dữ liệu đang không nhất quán.
            if (stops.isEmpty()) {
                throw new AppException(ErrorCode.INVALID_INPUT);
            }

            // Chỉ bổ sung tọa độ cho điểm giao chưa có tọa độ.
            // Không ghi đè các tọa độ đã được xác nhận.
            for (DeliveryStop stop : stops) {
                if (stop.getLatitude() != null
                        || stop.getLongitude() != null) {
                    throw new AppException(ErrorCode.INVALID_INPUT);
                }

                stop.setLatitude(request.destinationLat());
                stop.setLongitude(request.destinationLng());
            }
        }

        requirement.setDestinationLat(request.destinationLat());
        requirement.setDestinationLng(request.destinationLng());

        return toResponse(requirement);
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



    @Override
    @Transactional
    public DeliveryRequirementResponse extendDeadline(
            Long companyId,
            Long userId,
            Long requirementId,
            ExtendDeliveryDeadlineRequest request
    ) {
        DeliveryRequirement requirement = deliveryRequirementRepository
                .findByIdAndCompanyId(requirementId, companyId)
                .orElseThrow(() -> new AppException(
                        ErrorCode.DELIVERY_REQUIREMENT_NOT_FOUND
                ));

        Trip trip = requirement.getTrip();

        // Chỉ gia hạn khi chuyến đang vận chuyển.
        if (trip == null || trip.getStatus() != TripStatus.IN_TRANSIT) {
            throw new AppException(
                    ErrorCode.INVALID_DELIVERY_REQUIREMENT_TRANSITION
            );
        }

        LocalDateTime oldDeadline = requirement.getDeadline();
        LocalDateTime newDeadline = request.getNewDeadline();

        if (newDeadline == null
                || !newDeadline.isAfter(LocalDateTime.now())
                || (oldDeadline != null
                && !newDeadline.isAfter(oldDeadline))) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        String reason = request.getReason() == null
                ? ""
                : request.getReason().trim();

        if (reason.isBlank()) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        // Chỉ cập nhật deadline, không thay đổi ETA hoặc thông tin điểm giao.
        requirement.setDeadline(newDeadline);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_INPUT));

        if (user.getCompany() == null
                || !companyId.equals(user.getCompany().getId())) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        AuditLog auditLog = AuditLog.builder()
                .user(user)
                .actionType("DELIVERY_DEADLINE_EXTENDED")
                .entityName("DeliveryRequirement")
                .entityId(requirementId.toString())
                .oldValues(Map.of(
                        "deadline", oldDeadline == null
                                ? ""
                                : oldDeadline.toString()
                ))
                .newValues(Map.of(
                        "deadline", newDeadline.toString(),
                        "reason", reason,
                        "tripId", trip.getId()
                ))
                .createdAt(Instant.now())
                .build();

        auditLogRepository.save(auditLog);

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


    private DeliveryRequirementResponse toResponse(
            DeliveryRequirement requirement
    ) {
        Customer customer = requirement.getCustomer();

        List<Long> packageIds = requirement.getPackages()
                .stream()
                .map(CargoPackage::getId)
                .sorted()
                .toList();

        return DeliveryRequirementResponse.builder()
                .id(requirement.getId())
                .companyId(requirement.getCompany().getId())

                // Khách hàng gửi hàng
                .customerId(customer == null ? null : customer.getId())
                .customerName(customer == null ? null : customer.getName())

                // Thông tin giao hàng
                .destination(requirement.getDestination())
                .destinationLat(requirement.getDestinationLat())
                .destinationLng(requirement.getDestinationLng())
                .deadline(requirement.getDeadline())
                .priority(requirement.getPriority())

                // Thông tin kiện hàng
                .expectedPackageCount(requirement.getExpectedPackageCount())
                .receivedPackageCount(packageIds.size())
                .packageIds(packageIds)

                // Thông tin yêu cầu và chuyến
                .note(requirement.getNote())
                .status(requirement.getStatus())
                .tripId(requirement.getTrip() == null
                        ? null
                        : requirement.getTrip().getId())
                .tripCode(requirement.getTrip() == null
                        ? null
                        : requirement.getTrip().getTripCode())
                // Thông tin tạo
                .createdBy(requirement.getCreatedBy() == null
                        ? null
                        : requirement.getCreatedBy().getId())
                .createdAt(requirement.getCreatedAt())

                .build();
    }

}
