package fu.se184491.loadmaster_be.entity.trip;

import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;


import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @Column(name = "trip_code", length = 50, unique = true)
    private String tripCode;

    @Column(name = "departure_time")
    private LocalDateTime departureTime;

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
    private LocalDateTime createdAt;
}
