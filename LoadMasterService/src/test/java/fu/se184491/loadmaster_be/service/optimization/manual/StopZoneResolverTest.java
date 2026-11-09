package fu.se184491.loadmaster_be.service.optimization.manual;

import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.StopZoneResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.UnplacedPackageResponse;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StopZoneResolverTest {

    @Mock DeliveryStopRepository deliveryStopRepository;
    @Mock CargoPackageRepository cargoPackageRepository;

    StopZoneResolver resolver;
    Trip trip;

    @BeforeEach
    void setUp() {
        resolver = new StopZoneResolver(deliveryStopRepository, cargoPackageRepository);
        trip = Trip.builder().id(10L).build();
        // Hai điểm giao cùng thể tích hàng trong thùng 600 cm: điểm 71 (giao sau) ở [0, 295], điểm 70 (giao trước) ở [305, 600].
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(10L)).thenReturn(List.of(
                DeliveryStop.builder().id(70L).trip(trip).stopSequence(1).build(),
                DeliveryStop.builder().id(71L).trip(trip).stopSequence(2).build()));
    }

    private static PlacementDetailResponse box(long id, long stopId, double x) {
        return PlacementDetailResponse.builder().id(id).stopId(stopId)
                .posX(BigDecimal.valueOf(x)).posY(BigDecimal.ZERO).posZ(BigDecimal.ZERO)
                .packedLength(BigDecimal.valueOf(100)).packedWidth(BigDecimal.valueOf(100))
                .packedHeight(BigDecimal.valueOf(100)).build();
    }

    /** Kiện 102 của điểm 70 nằm sâu trong vùng điểm 71: một lần dỡ-xếp lại. */
    private static LoadPlanDetailResponse plan(Integer rehandling) {
        return LoadPlanDetailResponse.builder().id(5L).tripId(10L).stale(false).rehandlingCount(rehandling)
                .placements(new ArrayList<>(List.of(box(101L, 71L, 0), box(102L, 70L, 100))))
                .unplaced(new ArrayList<>()).build();
    }

    @Test
    void zonesAreAttachedWhenTheyGiveTheStoredRehandlingCount() {
        LoadPlanDetailResponse plan = plan(1);

        resolver.attach(plan, 600);

        assertThat(plan.getStopZones()).extracting(StopZoneResponse::getId, StopZoneResponse::getStopSequence,
                        StopZoneResponse::getStartXCm, StopZoneResponse::getEndXCm, StopZoneResponse::getVolumeRatio)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ZONE-70", 1, 305.0, 600.0, 0.5),
                        org.assertj.core.groups.Tuple.tuple("ZONE-71", 2, 0.0, 295.0, 0.5));
        assertThat(plan.getPlacements()).extracting(PlacementDetailResponse::getStopZoneId)
                .containsExactly("ZONE-71", "ZONE-71");
    }

    @Test
    void zonesStayEmptyWhenTheStoredCountFollowsAnotherRule() {
        LoadPlanDetailResponse plan = plan(0);

        resolver.attach(plan, 600);

        assertThat(plan.getStopZones()).isNull();
        assertThat(plan.getPlacements()).extracting(PlacementDetailResponse::getStopZoneId).containsOnlyNulls();
    }

    @Test
    void stalePlansAndPlansWithoutACountAreNotRebuilt() {
        LoadPlanDetailResponse stale = plan(1);
        stale.setStale(true);
        LoadPlanDetailResponse uncounted = plan(null);

        resolver.attach(stale, 600);
        resolver.attach(uncounted, 600);

        assertThat(stale.getStopZones()).isNull();
        assertThat(uncounted.getStopZones()).isNull();
        verify(deliveryStopRepository, never()).findByTripIdOrderByStopSequenceAsc(anyLong());
    }

    @Test
    void anUnplacedPackageWithoutAStopOfTheTripLeavesTheZonesUnknown() {
        LoadPlanDetailResponse plan = plan(1);
        plan.getUnplaced().add(UnplacedPackageResponse.builder().packageId(9L).build());
        when(deliveryStopRepository.findRequirementStopsByPackageId(9L)).thenReturn(List.of());

        resolver.attach(plan, 600);

        assertThat(plan.getStopZones()).isNull();
    }
}
