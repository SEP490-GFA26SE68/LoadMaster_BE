package fu.se184491.loadmaster_be.dto.response.trip;

import java.util.List;

/**
 * Kết quả đổi thứ tự điểm: số thứ tự mới của từng điểm, và các kiện bị che một phần lối dỡ theo thứ tự mới (cảnh báo, không chặn).
 */
public record ReorderStopsResponse(Long tripId, String tripCode, List<Stop> stops, List<Partial> partial) {

    public record Stop(Long stopId, Integer sequence) {
    }

    /** {@code packageCode}: mã in trên nhãn của kiện; {@code stopId}: điểm giao của kiện. */
    public record Partial(String packageCode, Long stopId) {
    }
}
