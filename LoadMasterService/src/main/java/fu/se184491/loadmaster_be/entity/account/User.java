package fu.se184491.loadmaster_be.entity.account;

import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.entity.company.Company;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "users",
        indexes = {
                @Index(
                        name = "idx_users_keycloak_id",
                        columnList = "keycloak_id"
                ),
                @Index(
                        name = "idx_users_company_id",
                        columnList = "company_id"
                )
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_users_keycloak_id",
                        columnNames = "keycloak_id"
                ),
                @UniqueConstraint(
                        name = "uk_users_email",
                        columnNames = "email"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "keycloak_id",
            nullable = false,
            updatable = false,
            length = 100
    )
    private String keycloakId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(
            name = "email",
            length = 150,
            nullable = false
    )
    private String email;

    @Column(
            name = "full_name",
            length = 150,
            nullable = false
    )
    private String fullName;

    @Column(
            name = "phone_number",
            length = 20
    )
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 30
    )
    private UserStatus status;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();

        if (createdAt == null) {
            createdAt = now;
        }

        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}