package fu.se184491.loadmaster_be.service.optimization.manual;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.optimization.ApproveEditsRequest;
import fu.se184491.loadmaster_be.dto.request.optimization.ApproveEditsRequest.EditedPlacement;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.dto.response.optimization.UnplacedPackageResponse;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanWriteRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanWriteRepository.NewPlacement;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.AxleConfig;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.AxleLoads;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Box;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.CenterOfGravity;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Obstacle;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Space;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ManualPlanApprovalServiceImpl implements ManualPlanApprovalService {

    private final LoadPlanQueryService loadPlanQueryService;
    private final LoadPlanRepository loadPlanRepository;
    private final LoadPlanWriteRepository writeRepository;
    private final TripRepository tripRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final CargoPackageRepository cargoPackageRepository;
    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional
    public LoadPlanDetailResponse approveEdits(Long planId, ApproveEditsRequest request) {
        // Đọc qua lớp đọc: phương án của công ty khác là LOAD_PLAN_NOT_FOUND.
        LoadPlanDetailResponse source = loadPlanQueryService.getPlan(planId);
        if (Boolean.TRUE.equals(source.getStale())) {
            throw new AppException(ErrorCode.PLAN_STALE);
        }
        Trip trip = tripRepository.findById(source.getTripId())
                .filter(found -> found.getStatus() == TripStatus.PLANNED)
                .orElseThrow(() -> new AppException(ErrorCode.PLAN_TRIP_NOT_PLANNED));
        VehicleType type = trip.getVehicle() == null ? null : trip.getVehicle().getVehicleType();
        if (type == null || type.getInnerLength() == null || type.getInnerWidth() == null
                || type.getInnerHeight() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        Map<Long, PlacementDetailResponse> byId = source.getPlacements().stream()
                .collect(Collectors.toMap(PlacementDetailResponse::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Map<Long, EditedPlacement> edits = editsOf(request, byId);
        Set<Long> pins = pinsOf(request, byId);
        Map<Long, CargoPackage> packages = packagesOf(source.getPlacements(), source.getUnplaced());

        List<Box> before = new ArrayList<>();
        List<Box> after = new ArrayList<>();
        Map<Long, double[]> newSize = new HashMap<>();
        Map<Long, Integer> newRotation = new HashMap<>();
        Set<Long> edited = new HashSet<>();
        for (PlacementDetailResponse placement : byId.values()) {
            Box original = boxOf(placement);
            before.add(original);
            EditedPlacement edit = edits.get(placement.getId());
            if (edit == null) {
                after.add(original);
                continue;
            }
            int oldRotation = rotationOf(placement);
            int rotation = edit.getRotationType() == null ? oldRotation : edit.getRotationType();
            CargoPackage cargo = packages.get(placement.getPackageId());
            if (rotation != oldRotation && !rotationAllowed(cargo, rotation)) {
                throw new AppException(ErrorCode.MANUAL_EDIT_ROTATION_NOT_ALLOWED);
            }
            double[] size = ManualLayoutValidator.orientedSize(baseSizeOf(placement, cargo, oldRotation), rotation);
            Box moved = new Box(placement.getId(), finite(edit.getPosX()), finite(edit.getPosY()),
                    finite(edit.getPosZ()), size[0], size[1], size[2], original.weightKg());
            if (rotation != oldRotation || differs(moved, original)) {
                if (Boolean.TRUE.equals(placement.getPinned()) && pins.contains(placement.getId())) {
                    // Kiện ghim giữ nguyên chỗ: muốn dời phải bỏ ghim trong cùng bản chỉnh.
                    throw new AppException(ErrorCode.MANUAL_EDIT_INVALID);
                }
                edited.add(placement.getId());
                newSize.put(placement.getId(), size);
                newRotation.put(placement.getId(), rotation);
            }
            after.add(moved);
        }
        boolean pinsChanged = !pins.equals(currentPins(byId));
        if (edited.isEmpty() && !pinsChanged) {
            throw new AppException(ErrorCode.MANUAL_EDIT_INVALID);
        }

        AxleConfig axles = axleConfigOf(type);
        ErrorCode violation = ManualLayoutValidator.firstViolation(spaceOf(type), before, after, edited, axles);
        if (violation != null) {
            throw new AppException(violation);
        }

        User approver = currentUserService.getCurrentUser();
        LoadPlan sourcePlan = loadPlanRepository.findById(planId)
                .orElseThrow(() -> new AppException(ErrorCode.LOAD_PLAN_NOT_FOUND));
        loadPlanRepository.unapproveAllByTripId(trip.getId());
        AxleLoads loads = ManualLayoutValidator.axleLoads(after, axles);
        CenterOfGravity cog = ManualLayoutValidator.centerOfGravity(after);
        // Chỉ đổi ghim thì cách xếp giữ nguyên: giữ số của bộ tối ưu.
        Integer rehandling = edited.isEmpty() ? sourcePlan.getRehandlingCount()
                : rehandlingOf(trip, type, byId.values(), after, source.getUnplaced(), packages);
        LoadPlan approved = loadPlanRepository.saveAndFlush(LoadPlan.builder()
                .job(sourcePlan.getJob())
                .planName(sourcePlan.getPlanName())
                .packedItemsCount(sourcePlan.getPackedItemsCount())
                .volumeUtilization(sourcePlan.getVolumeUtilization())
                .weightUtilization(sourcePlan.getWeightUtilization())
                .approved(true)
                .approvedBy(approver)
                .version((sourcePlan.getVersion() == null ? 1 : sourcePlan.getVersion()) + 1)
                .parentPlanId(planId)
                .cogX(cog == null ? null : decimal(cog.x(), 3))
                .cogY(cog == null ? null : decimal(cog.y(), 3))
                .cogZ(cog == null ? null : decimal(cog.z(), 3))
                .frontAxleLoad(loads == null ? null : decimal(loads.frontKg(), 2))
                .rearAxleLoad(loads == null ? null : decimal(loads.rearKg(), 2))
                .rehandlingCount(rehandling)
                .objective(sourcePlan.getObjective())
                .manuallyEdited(edited.isEmpty() ? sourcePlan.getManuallyEdited() : Boolean.TRUE)
                .stale(false)
                .build());

        List<NewPlacement> rows = new ArrayList<>();
        Map<Long, Box> afterById = after.stream().collect(Collectors.toMap(Box::id, Function.identity()));
        for (PlacementDetailResponse placement : byId.values()) {
            Box box = afterById.get(placement.getId());
            boolean moved = edited.contains(placement.getId());
            double[] size = moved ? newSize.get(placement.getId()) : sizeOf(placement);
            rows.add(new NewPlacement(placement.getPackageId(),
                    moved ? decimal(box.x(), 2) : placement.getPosX(),
                    moved ? decimal(box.y(), 2) : placement.getPosY(),
                    moved ? decimal(box.z(), 2) : placement.getPosZ(),
                    moved ? decimal(size[0], 2) : placement.getPackedLength(),
                    moved ? decimal(size[1], 2) : placement.getPackedWidth(),
                    moved ? decimal(size[2], 2) : placement.getPackedHeight(),
                    moved ? newRotation.get(placement.getId()) : placement.getRotationType(),
                    placement.getStepSequence(),
                    pins.contains(placement.getId()),
                    placement.getStopId()));
        }
        writeRepository.insertPlacements(approved.getId(), rows);
        writeRepository.copyUnplaced(planId, approved.getId());

        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("approved", true);
        newValues.put("manuallyEdited", Boolean.TRUE.equals(approved.getManuallyEdited()));
        newValues.put("sourcePlanId", planId);
        newValues.put("movedCount", edited.size());
        newValues.put("pinnedCount", pins.size());
        auditLogRepository.save(AuditLog.builder()
                .actionType("PLAN_APPROVED")
                .companyId(AuditCompany.ofPlan(approved))
                .entityName("LOAD_PLAN")
                .entityId(String.valueOf(approved.getId()))
                .oldValues(Map.of("approved", false))
                .newValues(newValues)
                .createdAt(Instant.now())
                .build());

        return loadPlanQueryService.getPlan(approved.getId());
    }

    private static Map<Long, EditedPlacement> editsOf(ApproveEditsRequest request,
                                                      Map<Long, PlacementDetailResponse> byId) {
        Map<Long, EditedPlacement> edits = new LinkedHashMap<>();
        if (request == null || request.getPlacements() == null) {
            return edits;
        }
        for (EditedPlacement edit : request.getPlacements()) {
            if (edit == null || edit.getPlacementId() == null || !byId.containsKey(edit.getPlacementId())
                    || edits.put(edit.getPlacementId(), edit) != null
                    || (edit.getRotationType() != null
                    && (edit.getRotationType() < 0 || edit.getRotationType() > 5))) {
                throw new AppException(ErrorCode.MANUAL_EDIT_INVALID);
            }
        }
        return edits;
    }

    private static Set<Long> pinsOf(ApproveEditsRequest request, Map<Long, PlacementDetailResponse> byId) {
        if (request == null || request.getPinnedPlacementIds() == null) {
            return currentPins(byId);
        }
        Set<Long> pins = new HashSet<>();
        for (Long id : request.getPinnedPlacementIds()) {
            if (id == null || !byId.containsKey(id)) {
                throw new AppException(ErrorCode.MANUAL_EDIT_INVALID);
            }
            pins.add(id);
        }
        return pins;
    }

    private static Set<Long> currentPins(Map<Long, PlacementDetailResponse> byId) {
        return byId.values().stream()
                .filter(placement -> Boolean.TRUE.equals(placement.getPinned()))
                .map(PlacementDetailResponse::getId)
                .collect(Collectors.toSet());
    }

    private Map<Long, CargoPackage> packagesOf(List<PlacementDetailResponse> placements,
                                               List<UnplacedPackageResponse> unplaced) {
        List<Long> ids = Stream.concat(placements.stream().map(PlacementDetailResponse::getPackageId),
                        unplaced == null ? Stream.empty() : unplaced.stream().map(UnplacedPackageResponse::getPackageId))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, CargoPackage> result = new HashMap<>();
        for (CargoPackage cargo : cargoPackageRepository.findAllById(ids)) {
            result.put(cargo.getId(), cargo);
        }
        return result;
    }

    /**
     * Số kiện nằm ngoài vùng điểm giao của mình trong cách xếp mới. Vùng dựng lại như dịch vụ tối ưu: mọi điểm giao của chuyến và
     * thể tích mọi kiện của lần tối ưu (đã xếp và không xếp được — phương án chưa lỗi thời nên điểm giao và hàng chưa đổi). Thiếu
     * dữ liệu để dựng vùng (kiện không có điểm giao, kiện không xếp được không rõ kích thước) thì không đoán: {@code null}.
     */
    private Integer rehandlingOf(Trip trip, VehicleType type, Collection<PlacementDetailResponse> placements, List<Box> after,
                                 List<UnplacedPackageResponse> unplaced, Map<Long, CargoPackage> packages) {
        List<DeliveryStop> stops = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId());
        if (stops == null || stops.isEmpty()) {
            return null;
        }
        Map<Long, Integer> sequenceOf = new LinkedHashMap<>();
        for (int i = 0; i < stops.size(); i++) {
            Integer sequence = stops.get(i).getStopSequence();
            sequenceOf.put(stops.get(i).getId(), sequence == null ? i + 1 : sequence);
        }
        Map<Long, Double> volume = new HashMap<>();
        Map<Long, Long> stopByBox = new HashMap<>();
        for (PlacementDetailResponse placement : placements) {
            if (placement.getStopId() == null || !sequenceOf.containsKey(placement.getStopId())) {
                return null;
            }
            stopByBox.put(placement.getId(), placement.getStopId());
            double[] size = sizeOf(placement);
            volume.merge(placement.getStopId(), size[0] * size[1] * size[2], Double::sum);
        }
        for (UnplacedPackageResponse item : unplaced == null ? List.<UnplacedPackageResponse>of() : unplaced) {
            Long stopId = item.getPackageId() == null ? null : deliveryStopRepository
                    .findRequirementStopsByPackageId(item.getPackageId()).stream()
                    .filter(stop -> stop.getTrip() != null && Objects.equals(stop.getTrip().getId(), trip.getId()))
                    .map(DeliveryStop::getId).findFirst().orElse(null);
            double[] size = realSizeOf(packages.get(item.getPackageId()));
            if (size == null && item.getLengthCm() != null && item.getWidthCm() != null && item.getHeightCm() != null) {
                size = new double[]{item.getLengthCm().doubleValue(), item.getWidthCm().doubleValue(),
                        item.getHeightCm().doubleValue()};
            }
            if (stopId == null || !sequenceOf.containsKey(stopId) || size == null) {
                return null;
            }
            volume.merge(stopId, size[0] * size[1] * size[2], Double::sum);
        }
        List<StopZoneLayout.StopLoad> loads = sequenceOf.entrySet().stream()
                .map(entry -> new StopZoneLayout.StopLoad(entry.getKey(), entry.getValue(),
                        volume.getOrDefault(entry.getKey(), 0.0)))
                .toList();
        List<StopZoneLayout.Zone> zones = StopZoneLayout.zones(type.getInnerLength(), loads);
        return StopZoneLayout.rehandlingCount(zones, after, stopByBox);
    }

    /**
     * Kích thước gốc (dài, rộng, cao) của kiện, như dịch vụ tối ưu đọc: kích thước thật của kiện, thiếu thì của loại kiện. Không khớp
     * kích thước đã xếp ở hướng hiện tại (dữ liệu kiện đã đổi) thì suy ngược từ kích thước đã xếp.
     */
    private static double[] baseSizeOf(PlacementDetailResponse placement, CargoPackage cargo, int rotation) {
        double[] packed = sizeOf(placement);
        double[] real = realSizeOf(cargo);
        if (real != null) {
            double[] oriented = ManualLayoutValidator.orientedSize(real, rotation);
            boolean matches = true;
            for (int i = 0; i < 3; i++) {
                matches &= Math.abs(oriented[i] - packed[i]) <= 0.05;
            }
            if (matches) {
                return real;
            }
        }
        return ManualLayoutValidator.baseSize(packed, rotation);
    }

    private static double[] realSizeOf(CargoPackage cargo) {
        if (cargo == null) {
            return null;
        }
        PackageType type = cargo.getPackageType();
        Double length = dimension(cargo.getActualLength(), type == null ? null : type.getLength());
        Double width = dimension(cargo.getActualWidth(), type == null ? null : type.getWidth());
        Double height = dimension(cargo.getActualHeight(), type == null ? null : type.getHeight());
        return length == null || width == null || height == null ? null : new double[]{length, width, height};
    }

    private static Double dimension(BigDecimal actual, Integer fallback) {
        if (actual != null && actual.signum() > 0) {
            return actual.doubleValue();
        }
        return fallback != null && fallback > 0 ? fallback.doubleValue() : null;
    }

    /** Như dịch vụ tối ưu: không có loại kiện thì không xoay. */
    private static boolean rotationAllowed(CargoPackage cargo, int rotation) {
        PackageType type = cargo == null ? null : cargo.getPackageType();
        if (type == null) {
            return false;
        }
        return ManualLayoutValidator.allowedRotations(Boolean.TRUE.equals(type.getRotationAllowed()),
                Boolean.TRUE.equals(type.getAllowRotateX()), Boolean.TRUE.equals(type.getAllowRotateY()),
                Boolean.TRUE.equals(type.getAllowRotateZ())).contains(rotation);
    }

    private static Box boxOf(PlacementDetailResponse placement) {
        double[] size = sizeOf(placement);
        double weight = placement.getWeightKg() == null ? 0 : placement.getWeightKg().doubleValue();
        return new Box(placement.getId(), number(placement.getPosX()), number(placement.getPosY()),
                number(placement.getPosZ()), size[0], size[1], size[2], weight);
    }

    private static double[] sizeOf(PlacementDetailResponse placement) {
        return new double[]{number(placement.getPackedLength()), number(placement.getPackedWidth()),
                number(placement.getPackedHeight())};
    }

    private static int rotationOf(PlacementDetailResponse placement) {
        Integer rotation = placement.getRotationType();
        return rotation == null || rotation < 0 || rotation > 5 ? 0 : rotation;
    }

    private static boolean differs(Box a, Box b) {
        return Math.abs(a.x() - b.x()) > ManualLayoutValidator.EPSILON
                || Math.abs(a.y() - b.y()) > ManualLayoutValidator.EPSILON
                || Math.abs(a.z() - b.z()) > ManualLayoutValidator.EPSILON;
    }

    private static Space spaceOf(VehicleType type) {
        List<Obstacle> obstacles = type.getObstacles() == null ? List.of() : type.getObstacles().stream()
                .map(o -> new Obstacle(number(o.getX()), number(o.getY()), number(o.getZ()),
                        o.getLength() == null ? 0 : o.getLength(), o.getWidth() == null ? 0 : o.getWidth(),
                        o.getHeight() == null ? 0 : o.getHeight(), Boolean.TRUE.equals(o.getLoadBearing())))
                .toList();
        return new Space(type.getInnerLength(), type.getInnerWidth(), type.getInnerHeight(), obstacles);
    }

    private static AxleConfig axleConfigOf(VehicleType type) {
        return new AxleConfig(nullable(type.getFrontAxleX()), nullable(type.getRearAxleX()),
                nullable(type.getFrontAxleLimitKg()), nullable(type.getRearAxleLimitKg()),
                nullable(type.getEmptyFrontAxleLoadKg()), nullable(type.getEmptyRearAxleLoadKg()));
    }

    private static double finite(BigDecimal value) {
        if (value == null) {
            throw new AppException(ErrorCode.MANUAL_EDIT_INVALID);
        }
        return value.doubleValue();
    }

    private static double number(BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }

    private static Double nullable(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static BigDecimal decimal(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
}
