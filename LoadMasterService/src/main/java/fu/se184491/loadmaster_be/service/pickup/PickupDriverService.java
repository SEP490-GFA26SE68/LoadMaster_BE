package fu.se184491.loadmaster_be.service.pickup;

import fu.se184491.loadmaster_be.constant.cargo.LabelMethod;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.pickup.PickupRequestStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopType;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.driver.StopPackageResponse;
import fu.se184491.loadmaster_be.dto.response.pickup.PickupRequestResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequest;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequestPackage;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.pickup.PickupRequestPackageRepository;
import fu.se184491.loadmaster_be.repository.pickup.PickupRequestRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.service.monitoring.MonitoringTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Việc của tài xế với kiện nhận dọc đường. Ở điểm nhận: đối chiếu từng kiện bằng mã QR / mã (kiện lên xe), hoàn tất điểm khi đủ kiện
 * (yêu cầu → {@code LOADED}). Ở điểm giao: kiện nhận dỡ như kiện thường ({@code POST /api/driver/packages/{id}/unload}); hoàn tất điểm
 * mà mọi kiện của yêu cầu đã giao thì yêu cầu → {@code DELIVERED}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PickupDriverService {

    private final PickupRequestRepository pickupRequestRepository;
    private final PickupRequestPackageRepository pickupRequestPackageRepository;
    private final CargoPackageRepository cargoPackageRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;

    /** Kiện phải nhận ở điểm nhận {@code stop} (yêu cầu đã duyệt hoặc đã nhận xong), theo yêu cầu rồi thứ tự kiện. */
    public List<StopPackageResponse> pickupPackagesAt(DeliveryStop stop) {
        if (stop == null || stop.kind() != DeliveryStopType.PICKUP) {
            return List.of();
        }
        List<StopPackageResponse> packages = new ArrayList<>();
        int order = 1;
        for (PickupRequest request : pickupRequestRepository.findByPickupStopId(stop.getId())) {
            if (request.getStatus() != PickupRequestStatus.APPROVED && request.getStatus() != PickupRequestStatus.LOADED) {
                continue;
            }
            for (PickupRequestPackage line : request.getPackages()) {
                CargoPackage cargo = line.getCargoPackage();
                if (cargo == null) {
                    continue;
                }
                packages.add(StopPackageResponse.builder()
                        .packageId(cargo.getId())
                        .packageName(line.getPackageCode())
                        .packageCode(line.getPackageCode())
                        .trackingBarcode(cargo.getTrackingBarcode())
                        .qrToken(cargo.getQrToken())
                        .weight(line.getWeightKg())
                        .unloadOrder(order++)
                        .unloaded(false)
                        .pickupRequestId(request.getId())
                        .picked(line.getPickedAt() != null)
                        .pickedAt(line.getPickedAt())
                        .build());
            }
        }
        return packages;
    }

    /**
     * Kiện nhận dọc đường trong {@code stopPackages} (kiện của một điểm giao) mà chưa có trong {@code listed}: chỉ mã kiện và mã yêu
     * cầu nhận — nơi gọi dựng dòng kiện như kiện thường.
     */
    public List<StopPackageResponse> pickupDeliveriesAt(List<CargoPackage> stopPackages, Set<Long> listed) {
        List<StopPackageResponse> rows = new ArrayList<>();
        for (CargoPackage cargo : stopPackages) {
            if (cargo.getId() == null || listed.contains(cargo.getId())) {
                continue;
            }
            pickupRequestPackageRepository.findByCargoPackageId(cargo.getId()).ifPresent(line -> rows.add(
                    StopPackageResponse.builder()
                            .packageId(cargo.getId())
                            .pickupRequestId(line.getPickupRequest() != null ? line.getPickupRequest().getId() : null)
                            .build()));
        }
        return rows;
    }

    /** Tài xế đối chiếu một kiện nhận bằng quét QR ở điểm nhận hiện tại: kiện lên xe. */
    @Transactional
    public PickupRequestResponse pick(Long packageId) {
        return pick(packageId, null);
    }

    /** Tài xế đối chiếu một kiện nhận ở điểm nhận hiện tại, bằng quét QR hoặc gõ mã ({@code method}, vắng là QR): kiện lên xe. */
    @Transactional
    public PickupRequestResponse pick(Long packageId, LabelMethod method) {
        User driver = currentUserService.getCurrentUser();
        PickupRequestPackage line = pickupRequestPackageRepository.findByCargoPackageId(packageId)
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_NOT_FOUND));
        PickupRequest request = line.getPickupRequest();
        Trip trip = request.getTrip();
        if (trip.getDriver() == null || !Objects.equals(trip.getDriver().getId(), driver.getId())) {
            throw new AppException(ErrorCode.TRIP_NOT_ASSIGNED_TO_DRIVER);
        }
        if (trip.getStatus() != TripStatus.IN_TRANSIT) {
            throw new AppException(ErrorCode.GPS_TRIP_NOT_IN_TRANSIT);
        }
        if (line.getPickedAt() != null) {
            throw new AppException(ErrorCode.PICKUP_PACKAGE_ALREADY_PICKED);
        }
        if (request.getStatus() != PickupRequestStatus.APPROVED) {
            throw new AppException(ErrorCode.INVALID_PICKUP_STATUS_TRANSITION);
        }
        DeliveryStop current = deliveryStopRepository
                .findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(trip.getId(), DeliveryStopStatus.COMPLETED)
                .orElseThrow(() -> new AppException(ErrorCode.NO_PENDING_DELIVERY_STOP));
        if (request.getPickupStop() == null || !Objects.equals(request.getPickupStop().getId(), current.getId())) {
            throw new AppException(ErrorCode.PICKUP_PACKAGE_NOT_AT_STOP);
        }
        if (current.getActualArrival() == null && current.getStatus() != DeliveryStopStatus.ARRIVED) {
            throw new AppException(ErrorCode.DELIVERY_STOP_NOT_ARRIVED);
        }
        LocalDateTime now = MonitoringTime.now();
        line.setPickedAt(now);
        line.setPickedBy(driver);
        line.setPickMethod(LabelMethod.orQr(method));
        pickupRequestPackageRepository.save(line);
        CargoPackage cargo = line.getCargoPackage();
        cargo.setStatus(PackageStatus.LOADED);
        cargoPackageRepository.save(cargo);
        audit(trip, driver, "PICKUP_PACKAGE_PICKED", request, Map.of("packageId", cargo.getId(), "stopId", current.getId()));
        return PickupRequestResponse.fromEntity(request);
    }

    /** Điểm nhận chỉ hoàn tất khi mọi kiện của các yêu cầu đã duyệt ở điểm đó đã lên xe. */
    public void requireComplete(DeliveryStop stop) {
        if (stop.kind() != DeliveryStopType.PICKUP) {
            return;
        }
        long missing = pickupRequestRepository.findByPickupStopId(stop.getId()).stream()
                .filter(request -> request.getStatus() == PickupRequestStatus.APPROVED)
                .flatMap(request -> request.getPackages().stream())
                .filter(line -> line.getPickedAt() == null)
                .count();
        if (missing > 0) {
            throw new AppException(ErrorCode.PICKUP_STOP_INCOMPLETE);
        }
    }

    /** Sau khi tài xế hoàn tất một điểm: điểm nhận đưa yêu cầu sang {@code LOADED}; điểm giao đưa yêu cầu đã giao đủ sang {@code DELIVERED}. */
    public void onStopCompleted(DeliveryStop stop, User driver) {
        LocalDateTime now = MonitoringTime.now();
        Trip trip = stop.getTrip();
        if (stop.kind() == DeliveryStopType.PICKUP) {
            for (PickupRequest request : pickupRequestRepository.findByPickupStopId(stop.getId())) {
                if (request.getStatus() != PickupRequestStatus.APPROVED) {
                    continue;
                }
                request.setStatus(PickupRequestStatus.LOADED);
                request.setLoadedAt(now);
                pickupRequestRepository.save(request);
                audit(trip, driver, "PICKUP_LOADED", request, Map.of("stopId", stop.getId(),
                        "packages", request.getPackages().size()));
            }
            return;
        }
        for (PickupRequest request : pickupRequestRepository.findByDeliveryStopIdAndStatusIn(stop.getId(),
                EnumSet.of(PickupRequestStatus.LOADED))) {
            boolean allDelivered = request.getPackages().stream().allMatch(line -> line.getCargoPackage() != null
                    && line.getCargoPackage().getStatus() == PackageStatus.DELIVERED);
            if (!allDelivered) {
                continue;
            }
            request.setStatus(PickupRequestStatus.DELIVERED);
            request.setDeliveredAt(now);
            pickupRequestRepository.save(request);
            audit(trip, driver, "PICKUP_DELIVERED", request, Map.of("stopId", stop.getId()));
        }
    }

    private void audit(Trip trip, User user, String action, PickupRequest request, Map<String, Object> values) {
        Map<String, Object> newValues = new LinkedHashMap<>(values);
        newValues.put("pickupId", request.getId());
        newValues.put("tripId", trip.getId());
        auditLogRepository.save(AuditLog.builder()
                .companyId(AuditCompany.ofTrip(trip))
                .actionType(action)
                .entityName("PickupRequest")
                .entityId(String.valueOf(request.getId()))
                .user(user)
                .newValues(newValues)
                .createdAt(Instant.now())
                .build());
        log.info("{} pickup request {} on trip {}", action, request.getId(), trip.getTripCode());
    }
}
