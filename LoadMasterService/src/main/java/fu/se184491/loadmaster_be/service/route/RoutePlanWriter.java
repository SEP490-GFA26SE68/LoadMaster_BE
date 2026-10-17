package fu.se184491.loadmaster_be.service.route;

import fu.se184491.loadmaster_be.entity.route.RouteLeg;
import fu.se184491.loadmaster_be.entity.route.RoutePlan;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.repository.route.RouteLegRepository;
import fu.se184491.loadmaster_be.repository.route.RoutePlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Canonical writer for structured route geometry ({@link RoutePlan} + {@link RouteLeg}).
 * Callers may still sync a short JSON cache on {@link Trip#getRoutePlan()} for legacy readers.
 */
@Service
@RequiredArgsConstructor
public class RoutePlanWriter {

    private final RoutePlanRepository routePlanRepository;
    private final RouteLegRepository routeLegRepository;

    public record LegDraft(
            int legSequence,
            DeliveryStop startStop,
            DeliveryStop endStop,
            int distanceMeters,
            int durationSeconds,
            String polyline,
            List<Map<String, Object>> steps
    ) {
        public LegDraft(
                int legSequence,
                DeliveryStop startStop,
                DeliveryStop endStop,
                int distanceMeters,
                int durationSeconds,
                String polyline
        ) {
            this(legSequence, startStop, endStop, distanceMeters, durationSeconds, polyline, null);
        }
    }

    @Transactional
    public RoutePlan persistNewActivePlan(
            Trip trip,
            Integer totalDistanceMeters,
            Integer totalDurationSeconds,
            List<LegDraft> legs
    ) {
        routePlanRepository.deactivateAllByTripId(trip.getId());

        int nextVersion = routePlanRepository.findByTripId(trip.getId()).size() + 1;
        RoutePlan plan = RoutePlan.builder()
                .trip(trip)
                .planVersion(nextVersion)
                .totalDistanceMeters(totalDistanceMeters)
                .totalDurationSeconds(totalDurationSeconds)
                .isActive(true)
                .build();
        RoutePlan saved = routePlanRepository.save(plan);

        List<RouteLeg> toSave = new ArrayList<>();
        if (legs != null) {
            for (LegDraft draft : legs) {
                toSave.add(RouteLeg.builder()
                        .routePlan(saved)
                        .legSequence(draft.legSequence())
                        .startStop(draft.startStop())
                        .endStop(draft.endStop())
                        .distanceMeters(draft.distanceMeters())
                        .durationSeconds(draft.durationSeconds())
                        .polyline(draft.polyline())
                        .steps(draft.steps())
                        .build());
            }
        }
        if (!toSave.isEmpty()) {
            routeLegRepository.saveAll(toSave);
        }
        return saved;
    }

    @Transactional
    public RoutePlan cloneActiveWithLegOverride(
            Trip trip,
            RoutePlan active,
            List<RouteLeg> currentLegs,
            Long targetLegId,
            Integer distanceMeters,
            Integer durationSeconds,
            String polyline
    ) {
        return cloneActiveWithLegOverride(
                trip, active, currentLegs, targetLegId, distanceMeters, durationSeconds, polyline, null);
    }

    @Transactional
    public RoutePlan cloneActiveWithLegOverride(
            Trip trip,
            RoutePlan active,
            List<RouteLeg> currentLegs,
            Long targetLegId,
            Integer distanceMeters,
            Integer durationSeconds,
            String polyline,
            List<Map<String, Object>> steps
    ) {
        List<LegDraft> drafts = new ArrayList<>();
        int totalDistance = 0;
        int totalDuration = 0;
        for (RouteLeg leg : currentLegs) {
            boolean match = leg.getId() != null && leg.getId().equals(targetLegId);
            int distance = match && distanceMeters != null ? distanceMeters : leg.getDistanceMeters();
            int duration = match && durationSeconds != null ? durationSeconds : leg.getDurationSeconds();
            String line = match && polyline != null ? polyline : leg.getPolyline();
            List<Map<String, Object>> stepList = match && steps != null ? steps : leg.getSteps();
            drafts.add(new LegDraft(
                    leg.getLegSequence(),
                    leg.getStartStop(),
                    leg.getEndStop(),
                    distance,
                    duration,
                    line,
                    stepList));
            totalDistance += distance;
            totalDuration += duration;
        }
        return persistNewActivePlan(trip, totalDistance, totalDuration, drafts);
    }
}
