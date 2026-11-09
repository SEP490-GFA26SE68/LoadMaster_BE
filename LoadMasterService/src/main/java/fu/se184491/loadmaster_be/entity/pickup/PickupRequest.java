package fu.se184491.loadmaster_be.entity.pickup;

import fu.se184491.loadmaster_be.constant.pickup.PickupRequestStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Yêu cầu nhận hàng dọc đường của một chuyến đang vận chuyển: điểm nhận, điểm giao, hạn giao và các kiện. Công ty là công ty của chuyến.
 * Kết quả mười luật của lần kiểm gần nhất nằm ở {@code validation_results}; chỗ của kiện nhận trên xe (xếp vào phần thùng đã trống lúc
 * duyệt) ở {@code layout}. Phương án đã duyệt của chuyến không đổi.
 */
@Entity
@Table(name = "pickup_requests", indexes = @Index(name = "idx_pickup_requests_trip_id", columnList = "trip_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PickupRequest {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "pickup_name", length = 150, nullable = false)
    private String pickupName;

    @Column(name = "pickup_address", length = 255, nullable = false)
    private String pickupAddress;

    @Column(name = "pickup_latitude", precision = 10, scale = 7, nullable = false)
    private BigDecimal pickupLatitude;

    @Column(name = "pickup_longitude", precision = 10, scale = 7, nullable = false)
    private BigDecimal pickupLongitude;

    @Column(name = "delivery_name", length = 150, nullable = false)
    private String deliveryName;

    @Column(name = "delivery_address", length = 255, nullable = false)
    private String deliveryAddress;

    @Column(name = "delivery_latitude", precision = 10, scale = 7, nullable = false)
    private BigDecimal deliveryLatitude;

    @Column(name = "delivery_longitude", precision = 10, scale = 7, nullable = false)
    private BigDecimal deliveryLongitude;

    /** Hạn giao, giờ Việt Nam; null là không có hạn. */
    @Column(name = "deadline")
    private LocalDateTime deadline;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private PickupRequestStatus status = PickupRequestStatus.PENDING;

    /** Mười kết quả {@code {rule, passed, code, params}} theo thứ tự luật của lần kiểm gần nhất. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_results", columnDefinition = "json")
    private List<Map<String, Object>> validationResults;

    @Column(name = "validated_at")
    private LocalDateTime validatedAt;

    @Column(name = "override_reason", length = 300)
    private String overrideReason;

    @Column(name = "reject_reason", length = 300)
    private String rejectReason;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "rejected_by")
    private User rejectedBy;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "pickup_stop_id")
    private DeliveryStop pickupStop;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "delivery_stop_id")
    private DeliveryStop deliveryStop;

    /** Chỗ của kiện nhận lúc duyệt: {@code placements}, {@code unplaced}, {@code plannedAt}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout", columnDefinition = "json")
    private Map<String, Object> layout;

    @Column(name = "loaded_at")
    private LocalDateTime loadedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Builder.Default
    @OneToMany(mappedBy = "pickupRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineIndex ASC")
    private List<PickupRequestPackage> packages = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = PickupRequestStatus.PENDING;
        }
    }
}
