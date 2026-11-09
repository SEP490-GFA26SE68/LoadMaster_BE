package fu.se184491.loadmaster_be.service.optimization.manual;

import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.StopZoneResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.UnplacedPackageResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Box;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dựng vùng theo điểm giao của một phương án như dịch vụ tối ưu dựng: mọi điểm giao của chuyến và thể tích mọi kiện của lần tối
 * ưu (đã xếp và không xếp được). Một chỗ cho cả bản chỉnh tay (đếm lại số dỡ-xếp lại) và lối đọc phương án (trả vùng cho màn).
 */
@Component
@RequiredArgsConstructor
public class StopZoneResolver {

    private final DeliveryStopRepository deliveryStopRepository;
    private final CargoPackageRepository cargoPackageRepository;

    /** Vùng của phương án, thứ tự và tỷ lệ thể tích theo điểm giao, và điểm giao của từng kiện đã xếp (theo mã chỗ xếp). */
    public record ResolvedZones(List<StopZoneLayout.Zone> zones, Map<Long, Integer> sequenceByStop,
                                Map<Long, Double> volumeRatioByStop, Map<Long, Long> stopByPlacement) {
    }

    /**
     * Thiếu dữ liệu để dựng vùng (chuyến không có điểm giao, kiện không có điểm giao của chuyến, kiện không xếp được không rõ kích
     * thước) thì không đoán: {@code null}.
     */
    public ResolvedZones resolve(Long tripId, double innerLength, Collection<PlacementDetailResponse> placements,
                                 List<UnplacedPackageResponse> unplaced) {
        List<DeliveryStop> stops = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(tripId);
        if (stops == null || stops.isEmpty()) {
            return null;
        }
        Map<Long, Integer> sequenceOf = new LinkedHashMap<>();
        for (int i = 0; i < stops.size(); i++) {
            Integer sequence = stops.get(i).getStopSequence();
            sequenceOf.put(stops.get(i).getId(), sequence == null ? i + 1 : sequence);
        }
        Map<Long, Double> volume = new HashMap<>();
        Map<Long, Long> stopByPlacement = new HashMap<>();
        for (PlacementDetailResponse placement : placements) {
            if (placement.getStopId() == null || !sequenceOf.containsKey(placement.getStopId())) {
                return null;
            }
            stopByPlacement.put(placement.getId(), placement.getStopId());
            volume.merge(placement.getStopId(), number(placement.getPackedLength()) * number(placement.getPackedWidth())
                    * number(placement.getPackedHeight()), Double::sum);
        }
        List<UnplacedPackageResponse> missing = unplaced == null ? List.of() : unplaced;
        List<Long> ids = missing.stream().map(UnplacedPackageResponse::getPackageId).filter(Objects::nonNull)
                .distinct().toList();
        Map<Long, CargoPackage> packages = new HashMap<>();
        if (!ids.isEmpty()) {
            for (CargoPackage cargo : cargoPackageRepository.findAllById(ids)) {
                packages.put(cargo.getId(), cargo);
            }
        }
        for (UnplacedPackageResponse item : missing) {
            Long stopId = item.getPackageId() == null ? null : deliveryStopRepository
                    .findRequirementStopsByPackageId(item.getPackageId()).stream()
                    .filter(stop -> stop.getTrip() != null && Objects.equals(stop.getTrip().getId(), tripId))
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
        double total = loads.stream().mapToDouble(StopZoneLayout.StopLoad::volumeCm3).sum();
        Map<Long, Double> ratio = new HashMap<>();
        for (StopZoneLayout.StopLoad load : loads) {
            ratio.put(load.stopId(), total > 0 ? load.volumeCm3() / total : 1.0 / loads.size());
        }
        return new ResolvedZones(StopZoneLayout.zones(innerLength, loads), sequenceOf, ratio, stopByPlacement);
    }

    /**
     * Gắn vùng vào phương án đọc ra ({@code stopZones}, {@code stopZoneId} của từng kiện). Chỉ khi vùng dựng lại cho ra đúng số
     * dỡ-xếp lại đã lưu; phương án lỗi thời, chưa có số, hay đếm theo luật cũ thì để trống.
     */
    public void attach(LoadPlanDetailResponse plan, Integer innerLength) {
        if (plan == null || innerLength == null || innerLength <= 0 || Boolean.TRUE.equals(plan.getStale())
                || plan.getRehandlingCount() == null || plan.getPlacements() == null || plan.getPlacements().isEmpty()) {
            return;
        }
        ResolvedZones resolved = resolve(plan.getTripId(), innerLength, plan.getPlacements(), plan.getUnplaced());
        if (resolved == null) {
            return;
        }
        List<Box> boxes = new ArrayList<>();
        for (PlacementDetailResponse placement : plan.getPlacements()) {
            boxes.add(new Box(placement.getId(), number(placement.getPosX()), number(placement.getPosY()),
                    number(placement.getPosZ()), number(placement.getPackedLength()), number(placement.getPackedWidth()),
                    number(placement.getPackedHeight()), 0));
        }
        int count = StopZoneLayout.rehandlingCount(resolved.zones(), boxes, resolved.stopByPlacement());
        if (count != plan.getRehandlingCount()) {
            return;
        }
        List<StopZoneResponse> zones = new ArrayList<>();
        for (StopZoneLayout.Zone zone : resolved.zones()) {
            zones.add(StopZoneResponse.builder()
                    .id(zoneId(zone))
                    .stopId(zone.stopId())
                    .stopSequence(resolved.sequenceByStop().get(zone.stopId()))
                    .startXCm(zone.startX())
                    .endXCm(zone.endX())
                    .volumeRatio(resolved.volumeRatioByStop().get(zone.stopId()))
                    .build());
        }
        for (PlacementDetailResponse placement : plan.getPlacements()) {
            StopZoneLayout.Zone zone = StopZoneLayout.zoneAt(resolved.zones(),
                    number(placement.getPosX()) + number(placement.getPackedLength()) / 2.0);
            placement.setStopZoneId(zone == null ? null : zoneId(zone));
        }
        plan.setStopZones(zones);
    }

    private static String zoneId(StopZoneLayout.Zone zone) {
        return "ZONE-" + zone.stopId();
    }

    /** Kích thước gốc (dài, rộng, cao) của kiện như dịch vụ tối ưu đọc: kích thước thật, thiếu thì của loại kiện. */
    static double[] realSizeOf(CargoPackage cargo) {
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

    private static double number(BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }
}
