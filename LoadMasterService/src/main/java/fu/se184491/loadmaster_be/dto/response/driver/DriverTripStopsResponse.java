package fu.se184491.loadmaster_be.dto.response.driver;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Các điểm của chuyến cho tài xế, kèm giờ đến và hạn giao ({@code GET /api/driver/trips/{id}/stops}). Mọi mốc giờ là
 * {@code LocalDateTime} giờ Việt Nam.
 *
 * <p>{@code plannedArrival}: giờ đến kế hoạch của tuyến; {@code deadline}: hạn giao của yêu cầu giao gắn với điểm.
 * {@code eta}: giờ đến dự kiến tính từ vị trí GPS mới nhất (cùng cách của màn Giám sát) — chỉ khi chuyến Đang vận chuyển, có GPS và điểm
 * chưa hoàn tất có toạ độ. {@code plannedStatus} / {@code etaStatus}: {@code OK}, {@code AT_RISK} hoặc {@code MISSED} của giờ đó so
 * với hạn; null khi không có hạn hoặc không có giờ.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverTripStopsResponse {

    private Long tripId;
    private String tripCode;
    private String status;
    /** Vị trí GPS mới nhất của chuyến; null khi chưa có. */
    private Location location;
    /** Đọc lại sau ngần này mili giây khi chuyến Đang vận chuyển; null khi không cần đọc lại. */
    private Integer refreshMs;
    private List<Stop> stops;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Location {
        private Double lat;
        private Double lng;
        /** {@code GPS} hoặc {@code SIMULATED}. */
        private String source;
        private LocalDateTime recordedAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Stop {
        private Long stopId;
        private Integer stopSequence;
        private String stopName;
        private String address;
        private String status;
        /** Số kiện của điểm (theo yêu cầu giao). */
        private Integer packageCount;
        private LocalDateTime plannedArrival;
        private LocalDateTime actualArrival;
        private LocalDateTime deadline;
        private String plannedStatus;
        private LocalDateTime eta;
        private String etaStatus;
    }
}
