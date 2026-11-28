package fu.se184491.loadmaster_be.service.assistant.stats;

import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.constant.assistant.AssistantRoute;
import fu.se184491.loadmaster_be.dto.response.assistant.AssistantRouteStatsResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.assistant.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bộ đếm theo ngày giờ Việt Nam: cộng trong bộ nhớ, đẩy sang nơi lưu bền, giữ lại khi nơi lưu lỗi. */
class RouteStatsServiceTest {

    // 03:00Z là 10:00 ngày 20/11 giờ Việt Nam
    private static final Instant T0 = Instant.parse("2026-11-20T03:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    private final AssistantProperties properties = new AssistantProperties();
    private final MutableClock clock = new MutableClock(T0);
    private final FakeStore store = new FakeStore();

    /** Nơi lưu giả: cộng như HINCRBY, có thể giả lỗi. */
    static final class FakeStore implements RouteStatsStore {
        final Map<LocalDate, Map<String, Long>> days = new HashMap<>();
        final Map<LocalDate, Duration> keep = new HashMap<>();
        boolean down;

        @Override
        public void add(LocalDate day, Map<String, Long> increments, Duration ttl) {
            if (down) {
                throw new IllegalStateException("redis down");
            }
            Map<String, Long> into = days.computeIfAbsent(day, d -> new HashMap<>());
            increments.forEach((field, delta) -> into.merge(field, delta, Long::sum));
            keep.put(day, ttl);
        }

        @Override
        public Map<String, Long> read(LocalDate day) {
            if (down) {
                throw new IllegalStateException("redis down");
            }
            return days.getOrDefault(day, Map.of());
        }
    }

    @Test
    void countsByRouteTopicAndLocale() {
        RouteStatsService stats = new RouteStatsService(properties, store, clock);
        stats.record(AssistantRoute.PREPARED, "plans", "vi");
        stats.record(AssistantRoute.PREPARED, "plans", "en");
        stats.record(AssistantRoute.MODEL, "plans", "vi");
        stats.record(AssistantRoute.SMALL_TALK, null, "vi");
        stats.record(AssistantRoute.CACHED, "not a topic!", "vi");

        AssistantRouteStatsResponse out = stats.stats(DAY, DAY);

        assertThat(out.total()).isEqualTo(5);
        assertThat(out.byRoute()).containsExactly(Map.entry("SMALL_TALK", 1L), Map.entry("PREPARED", 2L),
                Map.entry("ESCALATED", 0L), Map.entry("MODEL", 1L), Map.entry("CACHED", 1L));
        assertThat(out.byLocale()).containsExactly(Map.entry("en", 1L), Map.entry("vi", 4L));
        assertThat(out.byTopic()).extracting(AssistantRouteStatsResponse.Topic::topicId, AssistantRouteStatsResponse.Topic::total)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("plans", 3L),
                        org.assertj.core.groups.Tuple.tuple(null, 2L));
        assertThat(out.partial()).isFalse();
    }

    @Test
    void flushMovesCountsToTheStoreWithoutDoubleCounting() {
        RouteStatsService stats = new RouteStatsService(properties, store, clock);
        stats.record(AssistantRoute.PREPARED, "credit", "vi");
        stats.record(AssistantRoute.PREPARED, "credit", "vi");

        stats.flush();
        stats.record(AssistantRoute.PREPARED, "credit", "vi");

        assertThat(store.days.get(DAY)).containsExactly(Map.entry("PREPARED|credit|vi", 2L));
        assertThat(stats.stats(DAY, DAY).total()).isEqualTo(3);
        // Hash của ngày sống tới hết 90 ngày kể từ ngày đó
        assertThat(store.keep.get(DAY)).isEqualTo(Duration.between(T0,
                DAY.plusDays(90).atStartOfDay(RouteStatsService.VIETNAM).toInstant()));
    }

    @Test
    void storeDownKeepsCountsInMemoryAndMarksReadsPartial() {
        RouteStatsService stats = new RouteStatsService(properties, store, clock);
        stats.record(AssistantRoute.ESCALATED, "axle", "en");
        store.down = true;

        stats.flush();
        AssistantRouteStatsResponse whileDown = stats.stats(DAY, DAY);

        assertThat(whileDown.total()).isEqualTo(1);
        assertThat(whileDown.partial()).isTrue();

        store.down = false;
        stats.flush();
        assertThat(store.days.get(DAY)).containsExactly(Map.entry("ESCALATED|axle|en", 1L));
        assertThat(stats.stats(DAY, DAY).total()).isEqualTo(1);
    }

    @Test
    void withoutAStoreCountsStayInMemory() {
        RouteStatsService stats = new RouteStatsService(properties, null, clock);
        stats.record(AssistantRoute.MODEL, null, "vi");
        stats.flush();

        AssistantRouteStatsResponse out = stats.stats(DAY, DAY);
        assertThat(out.total()).isEqualTo(1);
        assertThat(out.partial()).isTrue();
    }

    @Test
    void daysFollowVietnamTimeAndRangesAreInclusive() {
        RouteStatsService stats = new RouteStatsService(properties, store, clock);
        stats.record(AssistantRoute.PREPARED, null, "vi");
        // 17:00Z là 00:00 ngày 21/11 giờ Việt Nam
        clock.set(Instant.parse("2026-11-20T17:00:00Z"));
        stats.record(AssistantRoute.PREPARED, null, "vi");

        assertThat(stats.stats(DAY, DAY).total()).isEqualTo(1);
        assertThat(stats.stats(DAY.plusDays(1), DAY.plusDays(1)).total()).isEqualTo(1);
        assertThat(stats.stats(DAY, DAY.plusDays(1)).total()).isEqualTo(2);
    }

    @Test
    void newTopicsBeyondTheDailyCapCountAsUnknown() {
        properties.getRouteStats().setMaxKeysPerDay(2);
        RouteStatsService stats = new RouteStatsService(properties, store, clock);
        stats.record(AssistantRoute.PREPARED, "plans", "vi");
        stats.record(AssistantRoute.PREPARED, "credit", "vi");
        stats.record(AssistantRoute.PREPARED, "axle", "vi");
        stats.record(AssistantRoute.PREPARED, "plans", "vi");

        assertThat(stats.stats(DAY, DAY).byTopic())
                .extracting(AssistantRouteStatsResponse.Topic::topicId, AssistantRouteStatsResponse.Topic::total)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("plans", 2L),
                        org.assertj.core.groups.Tuple.tuple("credit", 1L),
                        org.assertj.core.groups.Tuple.tuple(null, 1L));
    }

    @Test
    void memoryOlderThanRetentionIsDropped() {
        RouteStatsService stats = new RouteStatsService(properties, null, clock);
        stats.record(AssistantRoute.PREPARED, null, "vi");
        clock.set(T0.plus(Duration.ofDays(90)));
        stats.record(AssistantRoute.PREPARED, null, "vi");

        assertThat(stats.stats(DAY, DAY).total()).isZero();
        assertThat(stats.stats(DAY.plusDays(90), DAY.plusDays(90)).total()).isEqualTo(1);
    }

    @Test
    void rangeMustBeOrderedAndAtMostNinetyDays() {
        RouteStatsService stats = new RouteStatsService(properties, store, clock);
        assertThat(stats.stats(DAY.minusDays(89), DAY).total()).isZero();
        assertThatThrownBy(() -> stats.stats(DAY.minusDays(90), DAY))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> stats.stats(DAY, DAY.minusDays(1))).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> stats.stats(null, DAY)).isInstanceOf(AppException.class);
    }
}
