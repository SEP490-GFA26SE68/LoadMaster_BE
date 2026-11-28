package fu.se184491.loadmaster_be.service.assistant.stats;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Mỗi ngày một hash Redis {@code assistant:route-stats:<yyyy-MM-dd>}, trường là tổ hợp, giá trị là số đếm (HINCRBY). */
final class RedisRouteStatsStore implements RouteStatsStore {

    static final String PREFIX = "assistant:route-stats:";

    private final StringRedisTemplate redis;

    RedisRouteStatsStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void add(LocalDate day, Map<String, Long> increments, Duration keep) {
        String key = PREFIX + day;
        increments.forEach((field, delta) -> redis.opsForHash().increment(key, field, delta));
        redis.expire(key, keep);
    }

    @Override
    public Map<String, Long> read(LocalDate day) {
        Map<Object, Object> raw = redis.opsForHash().entries(PREFIX + day);
        Map<String, Long> out = new HashMap<>();
        raw.forEach((field, value) -> {
            try {
                out.put(String.valueOf(field), Long.parseLong(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                // Trường lạ không phải số đếm thì bỏ
            }
        });
        return out;
    }
}
