package fu.se184491.loadmaster_be.dto.optimization.engine;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationObjective;
import fu.se184491.loadmaster_be.dto.optimization.engine.ProblemRequest.PackageData;
import fu.se184491.loadmaster_be.dto.optimization.engine.ProblemRequest.StopData;
import fu.se184491.loadmaster_be.dto.optimization.engine.ProblemRequest.VehicleData;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Converts Spring Boot domain objects into a {@link ProblemRequest} for the
 * FastAPI Optimization Engine.
 *
 * Rotation encoding (0–5):
 *  0: L×W×H (base)     1: W×L×H (rotZ)
 *  2: L×H×W (rotX)     3: H×L×W (rotX+Z)
 *  4: W×H×L (rotY)     5: H×W×L (rotY+Z)
 */
@Component
public class ProblemRequestMapper {

    /**
     * Builds a {@link ProblemRequest} from trip + packages + config.
     *
     * @param trip       the trip (must have vehicle → vehicleType populated)
     * @param packages   cargo packages linked to this trip's delivery stops
     * @param stops      ordered delivery stops of the trip
     * @param objective  optimization objective enum
     * @param timeLimitSec engine computation time ceiling (seconds)
     * @param seed       optional random seed (may be null)
     * @param pinned     optional list of pre-fixed placements (may be null or empty)
     */
    public ProblemRequest toRequest(
            Trip trip,
            List<CargoPackage> packages,
            List<DeliveryStop> stops,
            OptimizationObjective objective,
            Integer timeLimitSec,
            Long seed,
            List<PackagePlacement> pinned) {

        VehicleData vehicleData = buildVehicleData(trip.getVehicle().getVehicleType());

        // Build a stop sequence index: stopId → 0-based index
        Map<Long, Integer> stopIndexMap = buildStopIndexMap(stops);

        List<PackageData> packageDataList = packages.stream()
                .map(pkg -> buildPackageData(pkg, stopIndexMap))
                .toList();

        List<StopData> stopDataList = stops.stream()
                .map(s -> StopData.builder()
                        .id(s.getId())
                        .sequence(s.getStopSequence())
                        .build())
                .toList();

        List<ProblemRequest.PinnedData> pinnedDataList =
                (pinned == null) ? Collections.emptyList() :
                pinned.stream()
                        .map(p -> ProblemRequest.PinnedData.builder()
                                .packageId(p.getCargoPackage() != null
                                        ? p.getCargoPackage().getId() : null)
                                .posX(p.getPosX())
                                .posY(p.getPosY())
                                .posZ(p.getPosZ())
                                .rotationType(p.getRotationType())
                                .build())
                        .toList();

        return ProblemRequest.builder()
                .vehicle(vehicleData)
                .packages(packageDataList)
                .stops(stopDataList)
                .pinned(pinnedDataList)
                .objective(toEngineObjective(objective))
                .timeLimitSec(timeLimitSec)
                .seed(seed)
                .build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private VehicleData buildVehicleData(VehicleType vt) {
        return VehicleData.builder()
                .innerLength(vt.getInnerLength())
                .innerWidth(vt.getInnerWidth())
                .innerHeight(vt.getInnerHeight())
                .maxPayloadKg(vt.getMaxPayloadKg())
                .doorWidth(vt.getDoorWidth())
                .doorHeight(vt.getDoorHeight())
                .build();
    }

    private PackageData buildPackageData(CargoPackage pkg, Map<Long, Integer> stopIndexMap) {
        PackageType pt = pkg.getPackageType();

        // Derive stopIndex: package → order → deliveryStop → stopId
        Integer stopIndex = null;
        if (pkg.getOrder() != null
                && pkg.getOrder().getDeliveryStop() != null
                && pkg.getOrder().getDeliveryStop().getId() != null) {
            stopIndex = stopIndexMap.get(pkg.getOrder().getDeliveryStop().getId());
        }

        return PackageData.builder()
                .id(pkg.getId())
                .length(pt != null ? pt.getLength() : null)
                .width(pt != null ? pt.getWidth() : null)
                .height(pt != null ? pt.getHeight() : null)
                .weightKg(pkg.getActualWeightKg())
                .allowedRotations(pt != null ? buildAllowedRotations(pt) : Collections.emptyList())
                .maxStackWeightKg(pt != null ? pt.getMaxStackWeightKg() : null)
                .fragile(pt != null ? Boolean.TRUE.equals(pt.getFragile()) : false)
                .stopIndex(stopIndex)
                .build();
    }

    /**
     * Converts the allowRotateX/Y/Z flags into the list of permitted rotation types (0–5).
     *
     * Rotation table:
     *   0: L×W×H — always allowed (base orientation)
     *   1: W×L×H — allowRotateZ
     *   2: L×H×W — allowRotateX
     *   3: H×L×W — allowRotateX && allowRotateZ
     *   4: W×H×L — allowRotateY
     *   5: H×W×L — allowRotateY && allowRotateZ
     */
    public static List<Integer> buildAllowedRotations(PackageType pt) {
        boolean rotX = Boolean.TRUE.equals(pt.getAllowRotateX());
        boolean rotY = Boolean.TRUE.equals(pt.getAllowRotateY());
        boolean rotZ = Boolean.TRUE.equals(pt.getAllowRotateZ());

        List<Integer> allowed = new ArrayList<>();
        allowed.add(0);                     // base — always
        if (rotZ)          allowed.add(1);
        if (rotX)          allowed.add(2);
        if (rotX && rotZ)  allowed.add(3);
        if (rotY)          allowed.add(4);
        if (rotY && rotZ)  allowed.add(5);
        return allowed;
    }

    private Map<Long, Integer> buildStopIndexMap(List<DeliveryStop> stops) {
        Map<Long, Integer> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i).getId() != null) {
                map.put(stops.get(i).getId(), i);
            }
        }
        return map;
    }

    /**
     * Maps Spring Boot enum → engine string literal used by FastAPI.
     */
    public static String toEngineObjective(OptimizationObjective objective) {
        if (objective == null) return "MAX_VOLUME_UTIL";
        return switch (objective) {
            case MAX_VOLUME    -> "MAX_VOLUME_UTIL";
            case AXLE_BALANCE  -> "MIN_HEIGHT";
        };
    }
}
