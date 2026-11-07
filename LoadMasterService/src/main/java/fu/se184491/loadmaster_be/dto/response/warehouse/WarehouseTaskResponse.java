package fu.se184491.loadmaster_be.dto.response.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseTaskResponse {

    private Long tripId;
    private String tripCode;
    private String vehicleInfo;
    private Integer totalPackages;
    private Long loadPlanId;
    private LocalDateTime departureTime;
    /** Trạng thái chuyến: {@code PLANNED} (chờ soạn) hoặc {@code LOADING} (đang xếp / xếp xong). */
    private String status;
    /** Phiên xếp của chuyến Đang xếp hàng; null khi chưa bắt đầu xếp. */
    private Long executionId;
    /** {@code IN_PROGRESS} | {@code COMPLETED}; null khi chưa bắt đầu xếp. */
    private String executionStatus;
    /**
     * Chuyến quay về lập kế hoạch vì kiện thiếu / hỏng, chờ điều phối viên tối ưu lại và duyệt (chỉ có khi hỏi
     * {@code includeAwaitingReplan=true}); null với mọi nhiệm vụ khác.
     */
    private Boolean awaitingReplan;
    /** {@code STAGING_SHORTAGE} | {@code DAMAGED_PACKAGE} khi {@code awaitingReplan}. */
    private String replanReason;
}
