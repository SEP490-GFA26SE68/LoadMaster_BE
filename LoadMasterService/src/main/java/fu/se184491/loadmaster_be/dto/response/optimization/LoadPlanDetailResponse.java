package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Một phương án xếp hàng kèm kiện đã xếp và kiện không xếp được.
 * Trọng tâm (cogX/Y/Z): cm. Tải trục: kg. Tỷ lệ sử dụng: 0..1.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoadPlanDetailResponse {
    private Long id;
    private Long tripId;
    private Long jobId;
    private String jobUuid;
    private String jobStatus;
    /** Mục tiêu của phương án (MAX_VOLUME | AXLE_BALANCE | MIN_REHANDLING); phương án cũ: mục tiêu của job. */
    private String objective;
    /** Nhãn phương án ứng viên của lần chạy ("A" | "B" | "C"); phương án cũ và bản chạy lại: null. */
    private String candidateLabel;
    private String algorithmName;
    private Long computationMs;
    private String planName;
    private Integer version;
    private Long parentPlanId;
    private Integer packedItemsCount;
    private BigDecimal volumeUtilization;
    private BigDecimal weightUtilization;
    private Boolean approved;
    private Long approvedById;
    private String approvedByName;
    private BigDecimal cogX;
    private BigDecimal cogY;
    private BigDecimal cogZ;
    private BigDecimal frontAxleLoad;
    private BigDecimal rearAxleLoad;
    private Integer rehandlingCount;
    /** Bản duyệt dựng từ phương án đã chỉnh tay (dời / xoay kiện). */
    private Boolean manuallyEdited;
    /** Phương án lỗi thời: hàng, xe hoặc điểm giao của chuyến đã đổi sau khi tối ưu; không duyệt được. */
    private Boolean stale;
    private List<PlacementDetailResponse> placements;
    private List<UnplacedPackageResponse> unplaced;
    /**
     * Vùng theo điểm giao, theo thứ tự điểm giao. Chỉ có khi vùng dựng lại từ chuyến cho ra đúng {@code rehandlingCount} đã
     * lưu; không thì null (phương án lỗi thời, chưa có số, hay đếm theo luật cũ).
     */
    private List<StopZoneResponse> stopZones;
}
