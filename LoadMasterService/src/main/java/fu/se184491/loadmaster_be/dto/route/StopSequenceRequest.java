package fu.se184491.loadmaster_be.dto.route;

import fu.se184491.loadmaster_be.dto.goong.LatLng;

import java.time.LocalDateTime;
import java.util.List;

public class StopSequenceRequest {

    private final LatLng origin;
    private final List<Stop> stops;
    /**
     * Travel duration in seconds. Row/column 0 represents the origin;
     * row/column i + 1 represents stops.get(i).
     */
    private final int[][] distanceMatrix;

    public StopSequenceRequest(LatLng origin, List<Stop> stops, int[][] distanceMatrix) {
        this.origin = origin;
        this.stops = stops;
        this.distanceMatrix = distanceMatrix;
    }

    public LatLng getOrigin() {
        return origin;
    }

    public List<Stop> getStops() {
        return stops;
    }

    public int[][] getDistanceMatrix() {
        return distanceMatrix;
    }

    public record Stop(
            Long id,
            double lat,
            double lng,
            LocalDateTime deadline,
            int packageCount
    ) {
    }
}
