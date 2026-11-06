package fu.se184491.loadmaster_be.service.pickup;

import fu.se184491.loadmaster_be.client.GoongMapsClient;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.pickup.PickupRequestStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.dto.goong.DirectionsResponse;
import fu.se184491.loadmaster_be.dto.goong.LatLng;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.driver.UnloadConfirmation;
import fu.se184491.loadmaster_be.entity.monitoring.GpsTracking;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequest;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequestPackage;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.entity.warehouse.Deviation;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.driver.UnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.monitoring.GpsTrackingRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.pickup.PickupRequestRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.warehouse.DeviationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.service.monitoring.MonitoringTime;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.AxleConfig;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Box;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Obstacle;
import fu.se184491.loadmaster_be.service.pickup.PickupRoutePath.Point;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dựng ngữ cảnh mười luật cho một yêu cầu nhận từ chuyến lúc này: xe và giới hạn của loại xe, vị trí xe (GPS gần nhất), các điểm của
 * tuyến kèm tiến độ giao, hàng còn trên xe theo phương án kho đã xếp (kể cả sai lệch vị trí đã ghi), kiện của yêu cầu nhận đã duyệt
 * trước, rồi xếp kiện nhận vào phần thùng đang trống. Chỉ đọc.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PickupContextBuilder {

    private static final double FALLBACK_SPEED_KMH = 30.0;
    private static final Set<PickupRequestStatus> EARLIER = EnumSet.of(PickupRequestStatus.APPROVED, PickupRequestStatus.LOADED);

    private final DeliveryStopRepository deliveryStopRepository;
    private final UnloadConfirmationRepository unloadConfirmationRepository;
    private final GpsTrackingRepository gpsTrackingRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final PackagePlacementRepository packagePlacementRepository;
    private final PlacementConfirmationRepository placementConfirmationRepository;
    private final DeviationRepository deviationRepository;
    private final PickupRequestRepository pickupRequestRepository;
    private final TripPackageResolver tripPackageResolver;
    private final GoongMapsClient goongMapsClient;

    /** Ngữ cảnh, các điểm của chuyến theo thứ tự tuyến (cùng chỉ số với {@code context.stops()}) và chỗ chèn điểm mới. */
    public record Built(PickupRules.Context context, List<DeliveryStop> stops, PickupRules.Insertion insertion) {
    }

    public Built build(Trip trip, PickupRequest request, String overrideReason) {
        VehicleType type = trip.getVehicle() != null ? trip.getVehicle().getVehicleType() : null;
        if (type == null || type.getInnerLength() == null || type.getInnerWidth() == null || type.getInnerHeight() == null
                || type.getMaxPayloadKg() == null) {
            throw new AppException(ErrorCode.PICKUP_ROUTE_UNAVAILABLE);
        }
        List<DeliveryStop> stops = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId());
        Set<Long> unloaded = new HashSet<>();
        for (DeliveryStop stop : stops) {
            for (UnloadConfirmation confirmation : unloadConfirmationRepository.findByDeliveryStopId(stop.getId())) {
                if (confirmation.getCargoPackage() != null) {
                    unloaded.add(confirmation.getCargoPackage().getId());
                }
            }
        }

        List<PickupRequest> earlier = pickupRequestRepository.findByTripIdAndStatusIn(trip.getId(), EARLIER).stream()
                .filter(other -> !Objects.equals(other.getId(), request.getId())).toList();
        Set<Long> pickupCargoIds = new HashSet<>();
        pickupRequestRepository.findByTripIdOrderByIdAsc(trip.getId()).forEach(other -> other.getPackages().forEach(line -> {
            if (line.getCargoPackage() != null) {
                pickupCargoIds.add(line.getCargoPackage().getId());
            }
        }));

        // Kiện thường còn trên xe theo điểm giao
        Map<Long, DeliveryStop> stopOfPackage = new HashMap<>();
        Map<Long, Integer> onboardByStop = new HashMap<>();
        double onboardKg = 0;
        List<String> classes = new ArrayList<>();
        for (DeliveryStop stop : stops) {
            for (CargoPackage cargo : tripPackageResolver.packagesOfStop(stop.getId())) {
                if (pickupCargoIds.contains(cargo.getId())) {
                    continue;
                }
                stopOfPackage.putIfAbsent(cargo.getId(), stop);
                if (cargo.getHandlingClass() != null) {
                    classes.add(cargo.getHandlingClass().name());
                }
                if (stop.getStatus() != DeliveryStopStatus.COMPLETED && !unloaded.contains(cargo.getId())
                        && cargo.getStatus() != PackageStatus.DELIVERED) {
                    onboardByStop.merge(stop.getId(), 1, Integer::sum);
                    onboardKg += weightOf(cargo);
                }
            }
        }
        // Kiện của yêu cầu nhận đã duyệt trước, chưa giao
        Map<Long, DeliveryStop> stopById = stops.stream().collect(Collectors.toMap(DeliveryStop::getId, stop -> stop));
        for (PickupRequest other : earlier) {
            Long deliveryStopId = other.getDeliveryStop() != null ? other.getDeliveryStop().getId() : null;
            for (PickupRequestPackage line : other.getPackages()) {
                CargoPackage cargo = line.getCargoPackage();
                if (cargo == null || unloaded.contains(cargo.getId()) || cargo.getStatus() == PackageStatus.DELIVERED) {
                    continue;
                }
                onboardKg += number(line.getWeightKg());
                if (deliveryStopId != null && stopById.containsKey(deliveryStopId)) {
                    onboardByStop.merge(deliveryStopId, 1, Integer::sum);
                }
            }
        }

        List<PickupRules.RouteStop> routeStops = new ArrayList<>();
        for (DeliveryStop stop : stops) {
            boolean completed = stop.getStatus() == DeliveryStopStatus.COMPLETED;
            Point location = pointOf(stop.getLatitude(), stop.getLongitude());
            if (!completed && location == null) {
                throw new AppException(ErrorCode.PICKUP_ROUTE_UNAVAILABLE);
            }
            routeStops.add(new PickupRules.RouteStop(String.valueOf(stop.getId()), location, completed,
                    onboardByStop.getOrDefault(stop.getId(), 0),
                    stop.kind() == fu.se184491.loadmaster_be.constant.trip.DeliveryStopType.DELIVERY));
        }

        Point position = position(trip, stops);
        Point delivery = pointOf(request.getDeliveryLatitude(), request.getDeliveryLongitude());
        PickupRules.Insertion insertion = delivery == null ? null : PickupRules.insertion(routeStops, delivery);

        // Kiện còn trên xe lúc kiện nhận lên: kiện của điểm hiện tại đã dỡ trước đó
        Long currentStopId = insertion != null ? stops.get(insertion.currentIndex()).getId() : null;
        List<FreedSpacePacker.Onboard> boxes = new ArrayList<>();
        for (Map.Entry<Box, DeliveryStop> entry : loadedBoxes(trip, unloaded, stopOfPackage).entrySet()) {
            DeliveryStop stop = entry.getValue();
            if (stop.getStatus() == DeliveryStopStatus.COMPLETED || Objects.equals(stop.getId(), currentStopId)) {
                continue;
            }
            boxes.add(new FreedSpacePacker.Onboard(entry.getKey(), stop.getStopSequence()));
        }
        double looseKg = 0;
        for (PickupRequest other : earlier) {
            DeliveryStop stop = other.getDeliveryStop();
            boolean gone = stop == null || stop.getStatus() == DeliveryStopStatus.COMPLETED
                    || Objects.equals(stop.getId(), currentStopId);
            List<Box> placed = layoutBoxes(other);
            for (Box box : placed) {
                if (!gone) {
                    boxes.add(new FreedSpacePacker.Onboard(box, stop.getStopSequence()));
                }
            }
            if (!gone) {
                looseKg += unplacedKg(other);
            }
        }

        double maxPayload = type.getMaxPayloadKg().doubleValue();
        double pickupRank = insertion == null ? Double.MAX_VALUE
                : insertion.reusedIndex() >= 0 ? stops.get(insertion.reusedIndex()).getStopSequence()
                : stops.get(insertion.currentIndex()).getStopSequence() + 0.5;
        List<FreedSpacePacker.Item> items = new ArrayList<>();
        List<PickupRules.Cargo> cargo = new ArrayList<>();
        for (PickupRequestPackage line : request.getPackages()) {
            double length = number(line.getLengthCm());
            double width = number(line.getWidthCm());
            double height = number(line.getHeightCm());
            double weight = number(line.getWeightKg());
            String handlingClass = line.getHandlingClass() != null ? line.getHandlingClass().name() : "STANDARD";
            items.add(new FreedSpacePacker.Item(line.getLineIndex(), length, width, height, weight,
                    "FRAGILE".equals(handlingClass)));
            cargo.add(new PickupRules.Cargo(length, width, height, weight, handlingClass));
        }
        FreedSpacePacker.Vehicle vehicle = new FreedSpacePacker.Vehicle(type.getInnerLength(), type.getInnerWidth(),
                type.getInnerHeight(), type.getDoorWidth() == null ? null : type.getDoorWidth().doubleValue(),
                type.getDoorHeight() == null ? null : type.getDoorHeight().doubleValue(), obstaclesOf(type), axleConfigOf(type));
        FreedSpacePacker.Result packing = FreedSpacePacker.pack(vehicle, boxes, items,
                Math.max(0, maxPayload - onboardKg - looseKg), pickupRank);

        PickupRules.Request facts = new PickupRules.Request(pointOf(request.getPickupLatitude(), request.getPickupLongitude()),
                delivery, request.getDeadline(), cargo);
        PickupRules.VehicleLimits limits = new PickupRules.VehicleLimits(maxPayload, type.getInnerLength(),
                type.getInnerWidth(), type.getInnerHeight(), nullable(type.getFrontAxleLimitKg()),
                nullable(type.getRearAxleLimitKg()), nullable(type.getMaxCogOffsetRatio()));
        String lockedClass = trip.getHandlingClassLock() != null && !trip.getHandlingClassLock().isBlank()
                ? trip.getHandlingClassLock() : classes.isEmpty() ? null : classes.getFirst();
        String reason = overrideReason != null && !overrideReason.isBlank() ? overrideReason : trip.getOverrideReason();
        PickupRules.Context context = new PickupRules.Context(facts, limits, position, MonitoringTime.now(), routeStops,
                onboardKg + looseKg, packing, lockedClass, reason, this::durationSeconds);
        return new Built(context, stops, insertion);
    }

    // ---------------------------------------------------------------------------------------------

    /** Hộp của kiện đã lên xe theo phương án kho đã xếp; sai lệch vị trí đã ghi thì lấy vị trí thật. */
    private Map<Box, DeliveryStop> loadedBoxes(Trip trip, Set<Long> unloaded, Map<Long, DeliveryStop> stopOfPackage) {
        LoadPlan plan = loadingExecutionRepository.findAllByLoadPlanJobTripId(trip.getId()).stream()
                .filter(execution -> execution.getStatus() == LoadingExecutionStatus.IN_PROGRESS
                        || execution.getStatus() == LoadingExecutionStatus.COMPLETED)
                .max((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(LoadingExecution::getLoadPlan)
                .orElseThrow(() -> new AppException(ErrorCode.NO_LOADED_PLAN));
        Set<Long> confirmed = placementConfirmationRepository.findByPlacementLoadPlanId(plan.getId()).stream()
                .map(confirmation -> confirmation.getPlacement() != null ? confirmation.getPlacement().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Deviation> deviations = new HashMap<>();
        for (Deviation deviation : deviationRepository.findByPlacementLoadPlanId(plan.getId())) {
            if (deviation.getPlacement() != null) {
                deviations.putIfAbsent(deviation.getPlacement().getId(), deviation);
            }
        }
        Map<Box, DeliveryStop> boxes = new java.util.LinkedHashMap<>();
        for (PackagePlacement placement : packagePlacementRepository.findByLoadPlanId(plan.getId())) {
            Deviation deviation = deviations.get(placement.getId());
            CargoPackage cargo = placement.getCargoPackage();
            if ((!confirmed.contains(placement.getId()) && deviation == null) || cargo == null
                    || unloaded.contains(cargo.getId()) || cargo.getStatus() == PackageStatus.DELIVERED) {
                continue;
            }
            DeliveryStop stop = stopOfPackage.getOrDefault(cargo.getId(), placement.getStopZone());
            if (stop == null || placement.getPackedLength() == null || placement.getPackedWidth() == null
                    || placement.getPackedHeight() == null) {
                continue;
            }
            boolean moved = deviation != null && deviation.getActualPosX() != null && deviation.getActualPosY() != null
                    && deviation.getActualPosZ() != null;
            double x = moved ? deviation.getActualPosX() : integer(placement.getPosX());
            double y = moved ? deviation.getActualPosY() : integer(placement.getPosY());
            double z = moved ? deviation.getActualPosZ() : integer(placement.getPosZ());
            boxes.put(new Box(cargo.getId(), x, y, z, placement.getPackedLength(), placement.getPackedWidth(),
                    placement.getPackedHeight(), weightOf(cargo)), stop);
        }
        return boxes;
    }

    @SuppressWarnings("unchecked")
    static List<Box> layoutBoxes(PickupRequest request) {
        Map<String, Object> layout = request.getLayout();
        if (layout == null || !(layout.get("placements") instanceof List<?> list)) {
            return List.of();
        }
        Map<Integer, Double> weights = new HashMap<>();
        request.getPackages().forEach(line -> weights.put(line.getLineIndex(), number(line.getWeightKg())));
        List<Box> boxes = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> placement = (Map<String, Object>) raw;
            int index = toInt(placement.get("packageIndex"));
            boxes.add(new Box(null, toDouble(placement.get("xCm")), toDouble(placement.get("yCm")),
                    toDouble(placement.get("zCm")), toDouble(placement.get("placedLengthCm")),
                    toDouble(placement.get("placedWidthCm")), toDouble(placement.get("placedHeightCm")),
                    weights.getOrDefault(index, 0.0)));
        }
        return boxes;
    }

    private static double unplacedKg(PickupRequest request) {
        Map<String, Object> layout = request.getLayout();
        Set<Integer> placed = new HashSet<>();
        if (layout != null && layout.get("placements") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    placed.add(toInt(raw.get("packageIndex")));
                }
            }
        }
        double kg = 0;
        for (PickupRequestPackage line : request.getPackages()) {
            CargoPackage cargo = line.getCargoPackage();
            boolean delivered = cargo != null && cargo.getStatus() == PackageStatus.DELIVERED;
            if (!placed.contains(line.getLineIndex()) && !delivered) {
                kg += number(line.getWeightKg());
            }
        }
        return kg;
    }

    /** GPS gần nhất; chưa có thì điểm đã hoàn tất gần nhất, rồi nơi xuất phát của chuyến. */
    private Point position(Trip trip, List<DeliveryStop> stops) {
        GpsTracking latest = gpsTrackingRepository.findFirstByTripIdOrderByRecordedAtDesc(trip.getId()).orElse(null);
        if (latest != null) {
            Point point = pointOf(latest.getLatitude(), latest.getLongitude());
            if (point != null) {
                return point;
            }
        }
        for (int i = stops.size() - 1; i >= 0; i--) {
            DeliveryStop stop = stops.get(i);
            if (stop.getStatus() == DeliveryStopStatus.COMPLETED) {
                Point point = pointOf(stop.getLatitude(), stop.getLongitude());
                if (point != null) {
                    return point;
                }
            }
        }
        Point origin = pointOf(trip.getOriginLatitude(), trip.getOriginLongitude());
        if (origin == null) {
            throw new AppException(ErrorCode.PICKUP_ROUTE_UNAVAILABLE);
        }
        return origin;
    }

    /** Thời gian chạy một chặng như màn Giám sát: chỉ đường của Goong, không có thì đường nối thẳng ở 30 km/h. */
    long durationSeconds(Point from, Point to) {
        try {
            DirectionsResponse directions = goongMapsClient.getDirections(new LatLng(from.lat(), from.lng()),
                    new LatLng(to.lat(), to.lng()));
            if (directions != null && directions.getRoutes() != null && !directions.getRoutes().isEmpty()) {
                long seconds = directions.getRoutes().getFirst().getDurationSeconds();
                if (seconds > 0) {
                    return seconds;
                }
            }
        } catch (Exception ex) {
            log.debug("Goong directions unavailable for pickup ETA, using haversine fallback: {}", ex.getMessage());
        }
        double hours = PickupRoutePath.haversineKm(from, to) / FALLBACK_SPEED_KMH;
        return Math.max(1, Math.round(hours * 3600));
    }

    private static List<Obstacle> obstaclesOf(VehicleType type) {
        return type.getObstacles() == null ? List.of() : type.getObstacles().stream()
                .map(o -> new Obstacle(number(o.getX()), number(o.getY()), number(o.getZ()),
                        o.getLength() == null ? 0 : o.getLength(), o.getWidth() == null ? 0 : o.getWidth(),
                        o.getHeight() == null ? 0 : o.getHeight(), Boolean.TRUE.equals(o.getLoadBearing())))
                .toList();
    }

    private static AxleConfig axleConfigOf(VehicleType type) {
        return new AxleConfig(nullable(type.getFrontAxleX()), nullable(type.getRearAxleX()),
                nullable(type.getFrontAxleLimitKg()), nullable(type.getRearAxleLimitKg()),
                nullable(type.getEmptyFrontAxleLoadKg()), nullable(type.getEmptyRearAxleLoadKg()));
    }

    static Point pointOf(BigDecimal lat, BigDecimal lng) {
        return lat == null || lng == null ? null : new Point(lat.doubleValue(), lng.doubleValue());
    }

    private static double weightOf(CargoPackage cargo) {
        if (cargo.getActualWeightKg() != null) {
            return cargo.getActualWeightKg().doubleValue();
        }
        return cargo.getPackageType() != null && cargo.getPackageType().getWeightKg() != null
                ? cargo.getPackageType().getWeightKg().doubleValue() : 0;
    }

    private static double number(BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }

    private static Double nullable(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static double integer(Integer value) {
        return value == null ? 0 : value;
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }
}
