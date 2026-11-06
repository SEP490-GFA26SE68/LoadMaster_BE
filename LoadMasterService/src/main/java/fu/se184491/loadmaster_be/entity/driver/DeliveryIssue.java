package fu.se184491.loadmaster_be.entity.driver;

import fu.se184491.loadmaster_be.constant.driver.DeliveryIssueKind;
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
 * Sự cố tài xế báo cho một kiện tại điểm giao (hỏng, thiếu, khách từ chối, khác). Kiện có sự cố không bắt buộc dỡ mới hoàn tất
 * điểm; kiện chưa dỡ lúc hoàn tất điểm là kiện hoàn trả ({@code returned = true}) — trạng thái kiện không có giá trị hoàn trả riêng
 * nên kiện giữ {@code LOADED}.
 */
@Entity
@Table(name = "delivery_issues")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryIssue {

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
    @Column(name = "kind", length = 20, nullable = false)
    private DeliveryIssueKind kind;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by")
    private User reportedBy;

    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt;

    /** Kiện chưa dỡ lúc hoàn tất điểm: ở lại xe, hoàn trả. Null khi điểm chưa hoàn tất. */
    @Column(name = "returned")
    private Boolean returned;
}
