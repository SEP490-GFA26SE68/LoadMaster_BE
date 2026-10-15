package fu.se184491.loadmaster_be.entity.monitoring;

import fu.se184491.loadmaster_be.constant.monitoring.LocationSource;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "gps_tracking", indexes = {
        @Index(name = "idx_gps_tracking_trip_id", columnList = "trip_id"),
        @Index(name = "idx_gps_tracking_recorded_at", columnList = "recorded_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GpsTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "latitude", precision = 10, scale = 7, nullable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7, nullable = false)
    private BigDecimal longitude;

    @Column(name = "speed_kmh")
    private Double speedKmh;

    @Column(name = "heading")
    private Double heading;

    @Builder.Default
    @ColumnDefault("'GPS'")
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20, nullable = false)
    private LocationSource source = LocationSource.GPS;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (recordedAt == null) {
            recordedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (source == null) {
            source = LocationSource.GPS;
        }
    }
}
