package fu.se184491.loadmaster_be.dto.optimization.engine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO sent from Spring Boot → FastAPI Optimization Engine.
 *
 * FastAPI endpoint: POST /api/v1/optimization/jobs
 *
 * All dimensions are in millimetres (mm).
 * All weights are in kilograms (kg).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProblemRequest {

    private VehicleData vehicle;
    private List<PackageData> packages;
    private List<StopData> stops;
    private List<PinnedData> pinned;

    /** "MAX_VOLUME_UTIL" | "MIN_HEIGHT" — mapped from OptimizationObjective */
    private String objective;

    /** Engine computation time ceiling in seconds (10–600) */
    private Integer timeLimitSec;

    /** Optional random seed for reproducibility */
    private Long seed;

    // ── Nested DTOs ──────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VehicleData {
        /** Inner cargo compartment dimensions (mm) */
        private Integer innerLength;
        private Integer innerWidth;
        private Integer innerHeight;

        /** Maximum total payload (kg) */
        private BigDecimal maxPayloadKg;

        /** Door opening dimensions — constrains package entry rotation (mm) */
        private Integer doorWidth;
        private Integer doorHeight;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PackageData {
        /** Spring Boot CargoPackage.id — engine echoes this back in result */
        private Long id;

        /** Physical dimensions (mm) */
        private Integer length;
        private Integer width;
        private Integer height;

        /** Actual measured weight (kg) */
        private BigDecimal weightKg;

        /**
         * Bitmask of allowed rotation types (0–5).
         * Computed from PackageType.allowRotateX/Y/Z flags.
         */
        private List<Integer> allowedRotations;

        /** Maximum stackable weight on top of this package (kg) */
        private BigDecimal maxStackWeightKg;

        /** Whether this package is fragile (affects stacking rules) */
        private Boolean fragile;

        /** Delivery stop index (0-based) within the trip's stop sequence */
        private Integer stopIndex;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StopData {
        private Long id;
        private Integer sequence;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PinnedData {
        private Long packageId;
        private Integer posX;
        private Integer posY;
        private Integer posZ;
        /** Rotation type (0–5) already fixed for this package */
        private Integer rotationType;
    }
}
