package fu.se184491.loadmaster_be.service.monitoring.Impl;

import fu.se184491.loadmaster_be.dto.response.monitoring.RerouteOptionResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RerouteOptionsTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 12, 8, 0);

    @Test
    void longLegOffersBypassRingRoadAndHighwayAtTheirOwnSpeeds() {
        // chặng 20 km, đường gốc 30 phút
        List<RerouteOptionResponse> options = RerouteServiceImpl.buildOptions(
                new RerouteServiceImpl.LegMetrics(20.0, 1800), AT);

        assertThat(options).extracting(RerouteOptionResponse::getRoute)
                .containsExactly("BYPASS", "RING_ROAD", "HIGHWAY");
        // 23 km / 50 km/h = 27,6 phút; 26 km / 55 km/h = 28,4 phút; 30 km / 80 km/h = 22,5 phút
        assertThat(options).extracting(RerouteOptionResponse::getDistanceKm).containsExactly(23.0, 26.0, 30.0);
        assertThat(options).extracting(RerouteOptionResponse::getDurationMinutes).containsExactly(28, 28, 23);
        assertThat(options.get(2).getEta()).startsWith("2026-11-12T08:22");
    }

    @Test
    void shortLegHasNoHighway() {
        List<RerouteOptionResponse> options = RerouteServiceImpl.buildOptions(
                new RerouteServiceImpl.LegMetrics(14.9, 1500), AT);

        assertThat(options).extracting(RerouteOptionResponse::getRoute).containsExactly("BYPASS", "RING_ROAD");
        // 14,9 × 1,15 = 17,135 km / 50 km/h = 1.234 giây, nhanh hơn đường gốc thì không tính chậm thêm
        assertThat(options.get(0).getExtraMs()).isZero();
    }
}
