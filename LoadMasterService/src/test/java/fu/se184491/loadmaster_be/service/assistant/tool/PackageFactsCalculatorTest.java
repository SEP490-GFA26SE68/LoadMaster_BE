package fu.se184491.loadmaster_be.service.assistant.tool;

import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Toạ độ ghi tay (cm, X tăng về phía cửa), kỳ vọng tính trước bằng tay. */
class PackageFactsCalculatorTest {

    /** A nằm trên sàn; B đặt lên A; C lơ lửng cách mặt A 1 cm; D trên sàn cạnh A, chạm cạnh B nhưng không đỡ nó. */
    private final PlacementDetailResponse a = box("A", 0, 0, 0, 60, 40, 40);
    private final PlacementDetailResponse b = box("B", 10, 0, 40, 40, 40, 30);
    private final PlacementDetailResponse c = box("C", 0, 0, 41, 20, 20, 20);
    private final PlacementDetailResponse d = box("D", 60, 0, 0, 30, 40, 40);
    private final List<PlacementDetailResponse> all = List.of(a, b, c, d);

    @Test
    void boxOnTopRestsOnTheOneBelowAndAOneCentimetreGapDoesNot() {
        assertThat(PackageFactsCalculator.restsOn(b, all)).containsExactly("A");
        assertThat(PackageFactsCalculator.restingOnIt(a, all)).containsExactly("B");
        assertThat(PackageFactsCalculator.restsOn(c, all)).isEmpty();
        assertThat(PackageFactsCalculator.onFloor(c, all)).isFalse();
    }

    @Test
    void touchingOnlyAtAnEdgeIsNotSupport() {
        PlacementDetailResponse onEdge = box("E", 60, 0, 40, 20, 40, 10);

        assertThat(PackageFactsCalculator.restsOn(onEdge, List.of(a, onEdge))).isEmpty();
        assertThat(PackageFactsCalculator.restsOn(onEdge, List.of(a, d, onEdge))).containsExactly("D");
    }

    @Test
    void floorMeansNothingBelowAndBaseAtTheFloor() {
        assertThat(PackageFactsCalculator.onFloor(a, all)).isTrue();
        assertThat(PackageFactsCalculator.onFloor(b, all)).isFalse();
        assertThat(PackageFactsCalculator.restsOn(a, all)).isEmpty();
        assertThat(PackageFactsCalculator.restingOnIt(d, all)).isEmpty();
    }

    @Test
    void distanceToTheRearDoorOfA520CentimetreBody() {
        PlacementDetailResponse nearDoor = box("N", 400, 0, 0, 60, 40, 40);

        assertThat(PackageFactsCalculator.distanceToDoorCm(nearDoor, 520)).isEqualTo(60.0);
        assertThat(PackageFactsCalculator.distanceToDoorCm(box("F", 460, 0, 0, 60, 40, 40), 520)).isEqualTo(0.0);
        assertThat(PackageFactsCalculator.distanceToDoorCm(nearDoor, null)).isNull();
    }

    @Test
    void outOfZoneNeedsAZone() {
        PlacementDetailResponse noZone = box("Z0", 0, 0, 0, 10, 10, 10);
        noZone.setStopId(7L);
        PlacementDetailResponse inZone = box("Z1", 0, 0, 0, 10, 10, 10);
        inZone.setStopId(7L);
        inZone.setStopZoneId("ZONE-7");
        PlacementDetailResponse outside = box("Z2", 0, 0, 0, 10, 10, 10);
        outside.setStopId(7L);
        outside.setStopZoneId("ZONE-8");

        assertThat(PackageFactsCalculator.outOfZone(noZone)).isNull();
        assertThat(PackageFactsCalculator.outOfZone(inZone)).isFalse();
        assertThat(PackageFactsCalculator.outOfZone(outside)).isTrue();
    }

    @Test
    void unloadOrderIsEarlierStopFirstThenLastLoadedFirst() {
        PlacementDetailResponse deepForStop2 = step("P1", 2, 1);
        PlacementDetailResponse middleForStop1 = step("P2", 1, 2);
        PlacementDetailResponse doorForStop1 = step("P3", 1, 3);
        PlacementDetailResponse noStop = step("P4", null, 4);

        List<PlacementDetailResponse> order = PackageFactsCalculator.unloadOrder(
                List.of(deepForStop2, noStop, middleForStop1, doorForStop1));

        assertThat(order).extracting(PlacementDetailResponse::getPackageCode).containsExactly("P3", "P2", "P1", "P4");
    }

    private static PlacementDetailResponse step(String code, Integer stopSequence, int stepSequence) {
        PlacementDetailResponse placement = box(code, 0, 0, 0, 10, 10, 10);
        placement.setStopSequence(stopSequence);
        placement.setStepSequence(stepSequence);
        return placement;
    }

    private static PlacementDetailResponse box(String code, int x, int y, int z, int length, int width, int height) {
        return PlacementDetailResponse.builder().packageCode(code)
                .posX(BigDecimal.valueOf(x)).posY(BigDecimal.valueOf(y)).posZ(BigDecimal.valueOf(z))
                .packedLength(BigDecimal.valueOf(length)).packedWidth(BigDecimal.valueOf(width))
                .packedHeight(BigDecimal.valueOf(height)).build();
    }
}
