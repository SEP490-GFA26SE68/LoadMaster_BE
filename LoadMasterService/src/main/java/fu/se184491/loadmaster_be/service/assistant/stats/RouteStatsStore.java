package fu.se184491.loadmaster_be.service.assistant.stats;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/** Nơi giữ bền bộ đếm theo ngày. Khoá trong một ngày là {@code ĐƯỜNG|chủ đề|ngôn ngữ} (chủ đề trống là không rõ). */
public interface RouteStatsStore {

    /** Cộng {@code increments} vào bộ đếm của {@code day}; bộ đếm của ngày đó tự hết sau {@code keep}. */
    void add(LocalDate day, Map<String, Long> increments, Duration keep);

    /** Bộ đếm đã lưu của {@code day}; chưa có thì rỗng. */
    Map<String, Long> read(LocalDate day);
}
