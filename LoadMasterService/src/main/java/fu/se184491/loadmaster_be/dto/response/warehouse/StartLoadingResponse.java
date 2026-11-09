package fu.se184491.loadmaster_be.dto.response.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Phiên xếp của một chuyến: bắt đầu xếp ({@code POST /api/warehouse/tasks/{tripId}/start}) và mở lại
 * phiên ({@code GET /api/warehouse/tasks/{tripId}}) trả cùng dạng.
 *
 * <p>Lòng thùng và cửa của xe ({@code innerLength}…{@code doorHeight}): cm, lấy từ loại xe đang gắn;
 * null khi xe chưa gắn loại hoặc loại chưa khai.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartLoadingResponse {

    private Long executionId;
    private Long loadPlanId;
    private Long tripId;
    private String tripCode;
    private String vehicleInfo;
    private String licensePlate;
    private String vehicleTypeName;
    private Integer innerLength;
    private Integer innerWidth;
    private Integer innerHeight;
    private Integer doorWidth;
    private Integer doorHeight;
    /** Tải trọng tối đa của xe (kg), lấy từ loại xe; null khi chưa khai. */
    private BigDecimal maxPayloadKg;
    private Integer totalPackages;
    private Integer confirmedCount;
    private Integer deviationCount;
    private Integer pendingCount;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<PlacementStepResponse> placements;

    /** Phiên có bước soạn hàng (bắt đầu với {@code staging: true}). */
    private Boolean stagingRequired;
    /** Mọi kiện đã soạn và không còn báo thiếu chờ quyết: xếp được. Phiên không soạn hàng luôn là true. */
    private Boolean stagingComplete;
    private Integer stagedCount;
    private Integer openShortageCount;
    /** Kiện hỏng bị bỏ lại kho. */
    private Integer damagedCount;
    /** Xác nhận tay của kho (soạn, xếp) còn chờ điều phối viên duyệt: còn thì chưa hoàn tất xếp được. */
    private Integer pendingManualCount;
    private String sealNumber;
    private LocalDateTime sealedAt;
    private String sealedByName;
}
