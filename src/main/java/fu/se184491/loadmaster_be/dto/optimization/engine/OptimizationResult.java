package fu.se184491.loadmaster_be.dto.optimization.engine;

import fu.se184491.loadmaster_be.constant.optimization.UnplacedReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO received from FastAPI Optimization Engine → Spring Boot.
 *
 * FastAPI returns this as the body of POST /api/v1/optimization/jobs response
 * (synchronous) or as a callback payload (async webhook).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptimizationResult {

    private List<PlacementData> placements;
    private List<UnplacedData> unplaced;
    private MetricsData metrics;

    // ── Nested DTOs ──────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PlacementData {
        /** Echoed back from ProblemRequest.PackageData.id */
        private Long packageId;

        /** Bottom-left-front corner position (mm, origin at vehicle front-left-bottom) */
        private Integer posX;
        private Integer posY;
        private Integer posZ;

        /** Oriented dimensions after rotation is applied (mm) */
        private Integer packedLength;
        private Integer packedWidth;
        private Integer packedHeight;

        /** Rotation type applied (0–5, same encoding as PackagePlacement.rotationType) */
        private Integer rotationType;

        /** Loading step index (1-based) — order packages are physically loaded */
        private Integer stepSequence;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UnplacedData {
        /** Echoed back from ProblemRequest.PackageData.id */
        private Long packageId;

        /**
         * Reason from engine. Mapped to {@link UnplacedReason} when persisting
         * to {@link fu.se184491.loadmaster_be.entity.optimization.UnplacedPackage}.
         */
        private String reason;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MetricsData {
        /** Ratio of packed volume to vehicle inner volume (0.0–1.0) */
        private BigDecimal volumeUtilization;

        /** Ratio of total package weight to vehicle maxPayload (0.0–1.0) */
        private BigDecimal weightUtilization;

        /** Number of packages successfully placed */
        private Integer packedCount;

        /** Engine wall-clock computation time (milliseconds) */
        private Long computationMs;
    }
}
