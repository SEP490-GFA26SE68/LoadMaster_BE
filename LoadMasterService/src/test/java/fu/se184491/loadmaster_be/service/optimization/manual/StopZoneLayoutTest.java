package fu.se184491.loadmaster_be.service.optimization.manual;

import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Box;
import fu.se184491.loadmaster_be.service.optimization.manual.StopZoneLayout.StopLoad;
import fu.se184491.loadmaster_be.service.optimization.manual.StopZoneLayout.Zone;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StopZoneLayoutTest {

    @Test
    void firstDeliveryStopSitsAtTheDoorAndZonesSplitTheLengthByVolume() {
        // 600 cm, ba điểm: còn 580 cm sau hai khoảng đệm; thể tích 1 : 2 : 1.
        List<Zone> zones = StopZoneLayout.zones(600, List.of(
                new StopLoad(1L, 1, 100), new StopLoad(2L, 2, 200), new StopLoad(3L, 3, 100)));

        assertThat(zones).containsExactly(
                new Zone(1L, 455, 600),
                new Zone(2L, 155, 445),
                new Zone(3L, 0, 145));
    }

    @Test
    void stopsWithoutCargoShareTheLengthEquallyWhenNothingHasVolume() {
        List<Zone> zones = StopZoneLayout.zones(410, List.of(new StopLoad(1L, 1, 0), new StopLoad(2L, 2, 0)));

        assertThat(zones).containsExactly(new Zone(1L, 210, 410), new Zone(2L, 0, 200));
    }

    @Test
    void centreInTheBufferBelongsToTheNearerZoneAndATieGoesDeeper() {
        List<Zone> zones = List.of(new Zone(1L, 210, 410), new Zone(2L, 0, 200));

        assertThat(StopZoneLayout.zoneAt(zones, 203).stopId()).isEqualTo(2L);
        assertThat(StopZoneLayout.zoneAt(zones, 208).stopId()).isEqualTo(1L);
        assertThat(StopZoneLayout.zoneAt(zones, 205).stopId()).isEqualTo(2L);
        assertThat(StopZoneLayout.zoneAt(List.of(), 5)).isNull();
    }

    @Test
    void rehandlingCountsPackagesWhoseCentreLiesInAnotherStopsZone() {
        List<Zone> zones = List.of(new Zone(1L, 210, 410), new Zone(2L, 0, 200));
        // Kiện 11 lấn qua mép vùng nhưng tâm (195) vẫn trong vùng điểm 2 của nó; kiện 12 của điểm 1 nằm sâu trong vùng điểm 2.
        List<Box> boxes = List.of(
                new Box(11L, 150, 0, 0, 90, 50, 50, 10),
                new Box(12L, 0, 0, 0, 100, 50, 50, 10),
                new Box(13L, 300, 0, 0, 100, 50, 50, 10));

        int count = StopZoneLayout.rehandlingCount(zones, boxes, Map.of(11L, 2L, 12L, 1L, 13L, 1L));

        assertThat(count).isEqualTo(1);
    }
}
