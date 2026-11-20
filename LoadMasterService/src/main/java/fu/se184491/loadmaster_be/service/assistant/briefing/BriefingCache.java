package fu.se184491.loadmaster_be.service.assistant.briefing;

import fu.se184491.loadmaster_be.dto.response.assistant.AssistantBriefingResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bản tin đã dựng, giữ {@code ttl} cho mỗi khoá, trong bộ nhớ của một máy chủ. Có giới hạn số mục: đầy thì bỏ mục ít
 * dùng nhất. Đồng hồ tiêm vào để test tua giờ.
 */
final class BriefingCache {

    private record Cached(AssistantBriefingResponse briefing, Instant expiresAt) {
    }

    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Cached> entries;

    BriefingCache(Duration ttl, int maxEntries, Clock clock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("Bộ đệm bản tin cần ít nhất một mục");
        }
        this.ttl = ttl;
        this.clock = clock;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > maxEntries;
            }
        };
    }

    synchronized Optional<AssistantBriefingResponse> get(String key) {
        Cached entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.briefing());
    }

    synchronized void put(String key, AssistantBriefingResponse briefing) {
        entries.put(key, new Cached(briefing, clock.instant().plus(ttl)));
    }

    synchronized int size() {
        return entries.size();
    }
}
