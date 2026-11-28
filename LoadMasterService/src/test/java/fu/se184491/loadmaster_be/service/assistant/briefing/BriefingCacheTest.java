package fu.se184491.loadmaster_be.service.assistant.briefing;

import fu.se184491.loadmaster_be.dto.response.assistant.AssistantBriefingResponse;
import fu.se184491.loadmaster_be.service.assistant.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BriefingCacheTest {

    private static final Instant T0 = Instant.parse("2026-11-21T01:00:00Z");

    private static AssistantBriefingResponse briefing(String bullet) {
        return new AssistantBriefingResponse(T0, List.of(bullet), List.of());
    }

    @Test
    void fullCacheDropsTheLeastRecentlyUsedEntry() {
        BriefingCache cache = new BriefingCache(Duration.ofHours(2), 2, new MutableClock(T0));
        cache.put("a", briefing("A"));
        cache.put("b", briefing("B"));
        cache.get("a");

        cache.put("c", briefing("C"));

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.get("a")).isPresent();
        assertThat(cache.get("c")).isPresent();
    }

    @Test
    void entryExpiresExactlyAtTheTtl() {
        MutableClock clock = new MutableClock(T0);
        BriefingCache cache = new BriefingCache(Duration.ofHours(2), 10, clock);
        cache.put("a", briefing("A"));

        clock.set(T0.plus(Duration.ofHours(2)).minusMillis(1));
        assertThat(cache.get("a")).isPresent();
        clock.set(T0.plus(Duration.ofHours(2)));
        assertThat(cache.get("a")).isEmpty();
        assertThat(cache.size()).isZero();
    }
}
