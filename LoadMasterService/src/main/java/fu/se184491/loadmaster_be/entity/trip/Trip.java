package fu.se184491.loadmaster_be.entity.trip;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.company.CompanyWarehouse;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;


import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "trips")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Trip {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_warehouse_id")
    private CompanyWarehouse originWarehouse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "vehicle_id",
            nullable = false
    )
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "driver_user_id"
    )
    private User driver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "created_by_dispatcher_id",
            nullable = false
    )
    private User createdByDispatcher;


    @Column(name = "trip_name", length = 150)
    private String tripName;

    @Column(name = "origin_name", length = 150)
    private String originName;

    @Column(name = "origin_address", length = 255)
    private String originAddress;

    @Column(name = "origin_latitude", precision = 10, scale = 7)
    private BigDecimal originLatitude;

    @Column(name = "origin_longitude", precision = 10, scale = 7)
    private BigDecimal originLongitude;

    @Column(name = "trip_code", length = 50, unique = true)
    private String tripCode;

    @Column(name = "departure_time")
    private LocalDateTime departureTime;

    /** Giờ tài xế bấm Xuất phát (chuyến thành IN_TRANSIT); chưa xuất phát, hoặc xuất phát trước khi có cột này, thì null. */
    @Column(name = "departed_at")
    private LocalDateTime departedAt;

    @Builder.Default
    @ColumnDefault("'DRAFT'")
    @Enumerated(EnumType.STRING) @Column(name = "status", length = 20, nullable = false)
    private TripStatus status = TripStatus.DRAFT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "route_plan", columnDefinition = "json")
    private Map<String, Object> routePlan;

    @Column(name = "handling_class_lock", length = 20)
    private String handlingClassLock;

    @Column(name = "override_reason", columnDefinition = "text")
    private String overrideReason;

    @Column(name = "created_at")
    private Instant createdAt;

    /** Giờ tài xế hoàn tất điểm giao cuối (chuyến thành DELIVERED); chuyến chưa giao xong hoặc giao xong trước khi có cột này thì null. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Lý do điều phối viên nhập khi huỷ chuyến; chuyến chưa huỷ thì null. */
    @Column(name = "cancel_reason", columnDefinition = "text")
    private String cancelReason;

    /** Trạng thái của chuyến ngay trước khi bị huỷ; chuyến chưa huỷ thì null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_from_status", length = 20)
    private TripStatus cancelledFromStatus;

    /** Số kiện thành hoàn trả khi chuyến bị huỷ lúc đang vận chuyển; chuyến khác thì null. */
    @Column(name = "cancel_returned_count")
    private Integer cancelReturnedCount;
}
