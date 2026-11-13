package fu.se184491.loadmaster_be.dto.request.trip;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Mọi điểm của chuyến theo thứ tự mới (mã số điểm). */
public record ReorderStopsRequest(@NotEmpty List<Long> stopIds) {
}
