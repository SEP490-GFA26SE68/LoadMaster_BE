package fu.se184491.loadmaster_be.entity.planning;

import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "delivery_requirements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(nullable = false, length = 255)
    private String destination;

    @Column(name = "destination_lat", precision = 10, scale = 7)
    private BigDecimal destinationLat;

    @Column(name = "destination_lng", precision = 10, scale = 7)
    private BigDecimal destinationLng;

    @Column(nullable = false)
    private LocalDateTime deadline;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryRequirementPriority priority = DeliveryRequirementPriority.NORMAL;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryRequirementStatus status = DeliveryRequirementStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder.Default
    @ManyToMany
    @JoinTable(
            name = "delivery_requirement_packages",
            joinColumns = @JoinColumn(name = "delivery_requirement_id"),
            inverseJoinColumns = @JoinColumn(name = "package_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_delivery_requirement_package",
                    columnNames = {"delivery_requirement_id", "package_id"}
            )
    )
    private Set<CargoPackage> packages = new LinkedHashSet<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
