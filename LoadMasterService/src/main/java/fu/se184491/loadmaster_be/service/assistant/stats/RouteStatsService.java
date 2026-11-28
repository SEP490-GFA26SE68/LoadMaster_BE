package fu.se184491.loadmaster_be.service.assistant.stats;

import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.constant.assistant.AssistantRoute;
import fu.se184491.loadmaster_be.dto.response.assistant.AssistantRouteStatsResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Bộ đếm xu hướng câu hỏi của khách: ngày (giờ Việt Nam) × đường trả lời × chủ đề × ngôn ngữ. Không giữ IP, mã khách hay
 * chữ nào — chỉ số đếm. Mỗi lượt chỉ cộng vào bộ nhớ (không chờ mạng); một luồng riêng đẩy phần cộng dồn sang Redis theo
 * nhịp {@code flush-seconds}. Redis không có hay đang lỗi thì phần chưa đẩy được ở lại bộ nhớ và được cộng vào số đọc ra,
 * nên bộ đếm vẫn chạy, chỉ không sống qua lần khởi động lại.
 */
@Slf4j
@Component
public class RouteStatsService {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final Pattern TOPIC = Pattern.compile("^[a-zA-Z]{1,40}$");

    private final AssistantProperties properties;
    /** {@code null}: không có Redis, chỉ đếm trong bộ nhớ. */
    private final RouteStatsStore store;
    private final Clock clock;
    /** Phần cộng dồn chưa đẩy sang nơi lưu bền. */
    private final Map<LocalDate, Map<String, Long>> pending = new HashMap<>();
    /** Các tổ hợp đã gặp mỗi ngày, để chặn số tổ hợp (chủ đề do trình duyệt gửi). */
    private final Map<LocalDate, Set<String>> seen = new HashMap<>();
    private ScheduledExecutorService flusher;
    private boolean failing;

    @Autowired
    public RouteStatsService(AssistantProperties properties, ObjectProvider<StringRedisTemplate> redis) {
        this(properties, storeOf(redis.getIfAvailable()), Clock.systemUTC());
    }

    public RouteStatsService(AssistantProperties properties, RouteStatsStore store, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.clock = clock;
    }

    private static RouteStatsStore storeOf(StringRedisTemplate redis) {
        return redis == null ? null : new RedisRouteStatsStore(redis);
    }

    @PostConstruct
    void start() {
        if (store == null) {
            log.info("Không có Redis: bộ đếm xu hướng của trợ lý chỉ giữ trong bộ nhớ");
            return;
        }
        int seconds = Math.max(1, properties.getRouteStats().getFlushSeconds());
        flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "assistant-route-stats");
            thread.setDaemon(true);
            return thread;
        });
        flusher.scheduleWithFixedDelay(this::flush, seconds, seconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (flusher != null) {
            flusher.shutdownNow();
            flush();
        }
    }

    /** Cộng một lượt. Chủ đề sai dạng coi như không rõ; ngôn ngữ khác {@code en} là {@code vi}. */
    public void record(AssistantRoute route, String topicId, String locale) {
        if (route == null) {
            return;
        }
        String topic = topicId != null && TOPIC.matcher(topicId).matches() ? topicId : null;
        String lang = "en".equalsIgnoreCase(locale) ? "en" : "vi";
        LocalDate day = today();
        synchronized (this) {
            Set<String> fields = seen.computeIfAbsent(day, d -> new HashSet<>());
            String field = field(route, topic, lang);
            if (!fields.contains(field) && fields.size() >= Math.max(1, properties.getRouteStats().getMaxKeysPerDay())) {
                field = field(route, null, lang);
            }
            fields.add(field);
            pending.computeIfAbsent(day, d -> new HashMap<>()).merge(field, 1L, Long::sum);
            LocalDate oldest = oldestKept(day);
            pending.keySet().removeIf(d -> d.isBefore(oldest));
            seen.keySet().removeIf(d -> d.isBefore(oldest));
        }
    }

    /** Đẩy phần cộng dồn sang Redis; lỗi thì giữ lại cho lần sau. */
    public void flush() {
        if (store == null) {
            return;
        }
        Map<LocalDate, Map<String, Long>> batch;
        synchronized (this) {
            if (pending.isEmpty()) {
                return;
            }
            batch = new TreeMap<>(pending);
            pending.clear();
        }
        List<LocalDate> days = new ArrayList<>(batch.keySet());
        for (int i = 0; i < days.size(); i++) {
            LocalDate day = days.get(i);
            try {
                store.add(day, batch.get(day), keepFor(day));
            } catch (RuntimeException e) {
                restore(batch, days.subList(i, days.size()));
                if (!failing) {
                    log.warn("Chưa đẩy được bộ đếm xu hướng của trợ lý sang Redis, giữ trong bộ nhớ: {}",
                            e.getClass().getSimpleName());
                }
                failing = true;
                return;
            }
        }
        failing = false;
    }

    /** Tổng trong khoảng ngày (gồm hai đầu), tối đa {@code retention-days} ngày. */
    public AssistantRouteStatsResponse stats(LocalDate from, LocalDate to) {
        int retention = Math.max(1, properties.getRouteStats().getRetentionDays());
        if (from == null || to == null || from.isAfter(to) || ChronoUnit.DAYS.between(from, to) + 1 > retention) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }
        boolean partial = store == null;
        Map<String, Long> counts = new HashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (!partial) {
                try {
                    store.read(day).forEach((field, count) -> counts.merge(field, count, Long::sum));
                } catch (RuntimeException e) {
                    log.warn("Không đọc được bộ đếm xu hướng từ Redis: {}", e.getClass().getSimpleName());
                    partial = true;
                }
            }
            synchronized (this) {
                pending.getOrDefault(day, Map.of()).forEach((field, count) -> counts.merge(field, count, Long::sum));
            }
        }
        return summarize(from, to, counts, partial);
    }

    private static AssistantRouteStatsResponse summarize(LocalDate from, LocalDate to, Map<String, Long> counts,
                                                         boolean partial) {
        Map<AssistantRoute, Long> byRoute = emptyRoutes();
        Map<String, Map<AssistantRoute, Long>> byTopic = new HashMap<>();
        Map<String, Long> byLocale = new TreeMap<>();
        long total = 0;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            String[] parts = entry.getKey().split("\\|", -1);
            if (parts.length != 3) {
                continue;
            }
            AssistantRoute route;
            try {
                route = AssistantRoute.valueOf(parts[0]);
            } catch (IllegalArgumentException e) {
                continue;
            }
            long count = entry.getValue();
            total += count;
            byRoute.merge(route, count, Long::sum);
            byTopic.computeIfAbsent(parts[1], t -> emptyRoutes()).merge(route, count, Long::sum);
            byLocale.merge(parts[2], count, Long::sum);
        }
        List<AssistantRouteStatsResponse.Topic> topics = byTopic.entrySet().stream()
                .map(entry -> new AssistantRouteStatsResponse.Topic(entry.getKey().isEmpty() ? null : entry.getKey(),
                        entry.getValue().values().stream().mapToLong(Long::longValue).sum(), named(entry.getValue())))
                .sorted(Comparator.comparingLong(AssistantRouteStatsResponse.Topic::total).reversed()
                        .thenComparing(topic -> topic.topicId() == null ? "" : topic.topicId()))
                .toList();
        return new AssistantRouteStatsResponse(from, to, total, named(byRoute), topics, byLocale, partial);
    }

    private static Map<AssistantRoute, Long> emptyRoutes() {
        Map<AssistantRoute, Long> out = new EnumMap<>(AssistantRoute.class);
        for (AssistantRoute route : AssistantRoute.values()) {
            out.put(route, 0L);
        }
        return out;
    }

    private static Map<String, Long> named(Map<AssistantRoute, Long> counts) {
        Map<String, Long> out = new LinkedHashMap<>();
        counts.forEach((route, count) -> out.put(route.name(), count));
        return out;
    }

    private synchronized void restore(Map<LocalDate, Map<String, Long>> batch, List<LocalDate> days) {
        LocalDate oldest = oldestKept(today());
        for (LocalDate day : days) {
            if (day.isBefore(oldest)) {
                continue;
            }
            Map<String, Long> into = pending.computeIfAbsent(day, d -> new HashMap<>());
            batch.get(day).forEach((field, count) -> into.merge(field, count, Long::sum));
        }
    }

    /** Hash của một ngày sống tới hết ngày thứ {@code retention-days} kể từ ngày đó. */
    private Duration keepFor(LocalDate day) {
        int retention = Math.max(1, properties.getRouteStats().getRetentionDays());
        Instant end = day.plusDays(retention).atStartOfDay(VIETNAM).toInstant();
        Duration left = Duration.between(clock.instant(), end);
        return left.compareTo(Duration.ofHours(1)) < 0 ? Duration.ofHours(1) : left;
    }

    private LocalDate oldestKept(LocalDate today) {
        return today.minusDays(Math.max(1, properties.getRouteStats().getRetentionDays()) - 1L);
    }

    /** Hôm nay theo giờ Việt Nam. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(VIETNAM));
    }


    private static String field(AssistantRoute route, String topic, String locale) {
        return route.name() + "|" + (topic == null ? "" : topic) + "|" + locale;
    }
}
