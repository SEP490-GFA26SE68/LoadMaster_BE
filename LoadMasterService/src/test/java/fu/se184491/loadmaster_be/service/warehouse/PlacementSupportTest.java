package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlacementSupportTest {

    private static PackagePlacement box(long id, int x, int y, int z, int l, int w, int h) {
        return PackagePlacement.builder().id(id).posX(x).posY(y).posZ(z)
                .packedLength(l).packedWidth(w).packedHeight(h).build();
    }

    @Test
    void boxOnTopOfTheTargetRestsOnIt() {
        PackagePlacement base = box(1, 0, 0, 0, 100, 100, 50);
        PackagePlacement top = box(2, 20, 20, 50, 40, 40, 30);

        assertEquals(1, PlacementSupport.restingCount(base, List.of(base, top)));
    }

    @Test
    void boxBesideOrFloatingAboveDoesNotRest() {
        PackagePlacement base = box(1, 0, 0, 0, 100, 100, 50);
        PackagePlacement beside = box(2, 100, 0, 0, 50, 50, 50);
        PackagePlacement edgeOnly = box(3, 100, 0, 50, 50, 50, 50);
        PackagePlacement higher = box(4, 0, 0, 60, 50, 50, 50);

        assertEquals(0, PlacementSupport.restingCount(base, List.of(base, beside, edgeOnly, higher)));
    }

    @Test
    void boxesWithoutCoordinatesAreSkipped() {
        PackagePlacement base = box(1, 0, 0, 0, 100, 100, 50);
        PackagePlacement unknown = PackagePlacement.builder().id(2L).posZ(50).build();

        assertEquals(0, PlacementSupport.restingCount(base, List.of(base, unknown)));
        assertEquals(0, PlacementSupport.restingCount(unknown, List.of(base, unknown)));
    }
}
