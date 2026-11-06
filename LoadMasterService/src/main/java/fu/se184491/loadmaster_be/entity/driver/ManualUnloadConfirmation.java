package fu.se184491.loadmaster_be.entity.driver;

import fu.se184491.loadmaster_be.constant.driver.ManualConfirmReason;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Xác nhận tay một kiện vì nhãn không đọc được: tài xế khi dỡ, hoặc kho khi soạn hàng / xếp ({@link #step}). Kiện được ghi đã dỡ / đã
 * soạn / đã xếp ngay để làm tiếp; điều phối viên duyệt (kiện giữ kết quả) hoặc từ chối kèm lý do (kiện về chưa xử lý). Còn xác nhận
 * {@code PENDING} của điểm thì chưa hoàn tất điểm được; của bước kho thì chưa xong xếp được. {@code delivery_stop_id} là điểm giao của kiện.
 */
@Entity
@Table(name = "manual_unload_confirmations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManualUnloadConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_stop_id", nullable = false)
    private DeliveryStop deliveryStop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id", nullable = false)
    private CargoPackage cargoPackage;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 20, nullable = false)
    private ManualConfirmReason reason;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ManualConfirmStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private User requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Lý do từ chối của điều phối viên. */
    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    /** Bước của xác nhận; null (bản ghi cũ) là bước dỡ. */
    @Enumerated(EnumType.STRING)
    @Column(name = "step", length = 20)
    private ManualConfirmStep step;

    /** Vị trí kiện trong phương án, chỉ ở bước xếp. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "placement_id")
    private PackagePlacement placement;

    /** Bước thật của xác nhận: bản ghi không có bước là bước dỡ. */
    public ManualConfirmStep effectiveStep() {
        return step != null ? step : ManualConfirmStep.UNLOADING;
    }

    public boolean isUnloadingStep() {
        return effectiveStep() == ManualConfirmStep.UNLOADING;
    }
}
