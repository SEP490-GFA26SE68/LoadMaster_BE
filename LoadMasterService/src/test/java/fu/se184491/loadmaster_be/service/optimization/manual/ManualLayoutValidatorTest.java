package fu.se184491.loadmaster_be.service.optimization.manual;

import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.AxleConfig;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.AxleLoads;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Box;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Obstacle;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Space;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ManualLayoutValidatorTest {

    private static final Space TRUCK = new Space(600, 240, 240, List.of());
    private static final AxleConfig NO_AXLES = new AxleConfig(null, null, null, null, null, null);

    private static Box box(long id, double x, double y, double z) {
        return new Box(id, x, y, z, 100, 100, 100, 50);
    }

    @Test
    void moveIntoFreeFloorIsValid() {
        List<Box> before = List.of(box(1, 0, 0, 0), box(2, 100, 0, 0));
        List<Box> after = List.of(box(1, 0, 0, 0), box(2, 300, 120.5, 0));

        assertThat(ManualLayoutValidator.firstViolation(TRUCK, before, after, Set.of(2L), NO_AXLES)).isNull();
    }

    @Test
    void boxPastTheRearWallIsOutOfBounds() {
        List<Box> before = List.of(box(1, 0, 0, 0));
        List<Box> after = List.of(box(1, 500.5, 0, 0));

        assertThat(ManualLayoutValidator.firstViolation(TRUCK, before, after, Set.of(1L), NO_AXLES))
                .isEqualTo(ErrorCode.MANUAL_EDIT_OUT_OF_BOUNDS);
    }

    @Test
    void touchingFacesDoNotOverlapButIntersectingBoxesDo() {
        List<Box> before = List.of(box(1, 0, 0, 0), box(2, 300, 0, 0));
        List<Box> touching = List.of(box(1, 0, 0, 0), box(2, 100, 0, 0));
        List<Box> intersecting = List.of(box(1, 0, 0, 0), box(2, 99, 0, 0));

        assertThat(ManualLayoutValidator.firstViolation(TRUCK, before, touching, Set.of(2L), NO_AXLES)).isNull();
        assertThat(ManualLayoutValidator.firstViolation(TRUCK, before, intersecting, Set.of(2L), NO_AXLES))
                .isEqualTo(ErrorCode.MANUAL_EDIT_OVERLAP);
    }

    @Test
    void boxInsideAnObstacleOverlaps() {
        Space withCooler = new Space(600, 240, 240, List.of(new Obstacle(0, 0, 0, 60, 240, 80, true)));
        List<Box> before = List.of(box(1, 200, 0, 0));
        List<Box> after = List.of(box(1, 30, 0, 0));

        assertThat(ManualLayoutValidator.firstViolation(withCooler, before, after, Set.of(1L), NO_AXLES))
                .isEqualTo(ErrorCode.MANUAL_EDIT_OVERLAP);
    }

    @Test
    void floatingBoxIsUnsupported() {
        List<Box> before = List.of(box(1, 0, 0, 0));
        List<Box> after = List.of(box(1, 0, 0, 50));

        assertThat(ManualLayoutValidator.firstViolation(TRUCK, before, after, Set.of(1L), NO_AXLES))
                .isEqualTo(ErrorCode.MANUAL_EDIT_UNSUPPORTED);
    }

    @Test
    void boxOnTopOfAnotherNeedsSeventyPercentSupport() {
        List<Box> before = List.of(box(1, 0, 0, 0), box(2, 300, 0, 0));
        // 75 % của đáy nằm trên kiện 1: đạt; 60 %: không đạt.
        List<Box> enough = List.of(box(1, 0, 0, 0), box(2, 25, 0, 100));
        List<Box> tooLittle = List.of(box(1, 0, 0, 0), box(2, 40, 0, 100));

        assertThat(ManualLayoutValidator.firstViolation(TRUCK, before, enough, Set.of(2L), NO_AXLES)).isNull();
        assertThat(ManualLayoutValidator.firstViolation(TRUCK, before, tooLittle, Set.of(2L), NO_AXLES))
                .isEqualTo(ErrorCode.MANUAL_EDIT_UNSUPPORTED);
    }

    @Test
    void movingTheSupporterAwayLeavesTheBoxAboveUnsupported() {
        List<Box> before = List.of(box(1, 0, 0, 0), box(2, 0, 0, 100));
        List<Box> after = List.of(box(1, 300, 0, 0), box(2, 0, 0, 100));

        assertThat(ManualLayoutValidator.firstViolation(TRUCK, before, after, Set.of(1L), NO_AXLES))
                .isEqualTo(ErrorCode.MANUAL_EDIT_UNSUPPORTED);
    }

    @Test
    void loadBearingObstacleTopSupportsABox() {
        Space withShelf = new Space(600, 240, 240, List.of(new Obstacle(0, 0, 0, 100, 100, 80, true)));
        List<Box> before = List.of(box(1, 300, 0, 0));
        List<Box> after = List.of(box(1, 0, 0, 80));

        assertThat(ManualLayoutValidator.firstViolation(withShelf, before, after, Set.of(1L), NO_AXLES)).isNull();
    }

    @Test
    void axleLoadsFollowTheLeverModel() {
        // Trục trước x = 0, trục sau x = 400; một kiện 1.000 kg tâm ở x = 300 dồn 750 kg lên trục sau.
        AxleConfig axles = new AxleConfig(0.0, 400.0, 3000.0, 5000.0, 1000.0, 1500.0);
        Box heavy = new Box(1L, 250, 0, 0, 100, 100, 100, 1000);

        AxleLoads loads = ManualLayoutValidator.axleLoads(List.of(heavy), axles);

        assertThat(loads.frontKg()).isCloseTo(1250, within(1e-6));
        assertThat(loads.rearKg()).isCloseTo(2250, within(1e-6));
    }

    @Test
    void moveThatOverloadsTheRearAxleIsRejected() {
        AxleConfig axles = new AxleConfig(0.0, 400.0, 3000.0, 2000.0, 1000.0, 1500.0);
        Box front = new Box(1L, 0, 0, 0, 100, 100, 100, 1000);
        Box rear = new Box(1L, 450, 0, 0, 100, 100, 100, 1000);

        assertThat(ManualLayoutValidator.firstViolation(TRUCK, List.of(front), List.of(rear), Set.of(1L), axles))
                .isEqualTo(ErrorCode.MANUAL_EDIT_AXLE_OVERLOAD);
    }

    @Test
    void orientationCodesMatchTheOptimizer() {
        double[] base = {120, 80, 60};

        assertThat(ManualLayoutValidator.orientedSize(base, 0)).containsExactly(120, 80, 60);
        assertThat(ManualLayoutValidator.orientedSize(base, 1)).containsExactly(80, 120, 60);
        assertThat(ManualLayoutValidator.orientedSize(base, 3)).containsExactly(80, 60, 120);
        assertThat(ManualLayoutValidator.orientedSize(base, 5)).containsExactly(60, 80, 120);
        for (int code = 0; code < 6; code++) {
            assertThat(ManualLayoutValidator.baseSize(ManualLayoutValidator.orientedSize(base, code), code))
                    .containsExactly(120, 80, 60);
        }
    }

    @Test
    void allowedRotationsFollowThePackageType() {
        assertThat(ManualLayoutValidator.allowedRotations(false, true, true, true)).containsExactly(0);
        // Chỉ xoay quanh trục đứng (Z): đổi dài và rộng, giữ chiều cao.
        assertThat(ManualLayoutValidator.allowedRotations(true, false, false, true)).containsExactlyInAnyOrder(0, 1);
        assertThat(ManualLayoutValidator.allowedRotations(true, true, true, true))
                .containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5);
    }
}
