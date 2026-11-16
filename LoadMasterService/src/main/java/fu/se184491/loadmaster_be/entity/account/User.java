package fu.se184491.loadmaster_be.entity.account;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.entity.company.Company;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
@Check(name = "chk_users_updated_after_created", constraints = "updated_at >= created_at")
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
            name = "user_role_type",
            length = 30,
            nullable = false
    )
    private UserRole userRoleType;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "user_type",
            length = 20
    )
    @Builder.Default
    private fu.se184491.loadmaster_be.constant.account.UserType userType = fu.se184491.loadmaster_be.constant.account.UserType.OFFICIAL;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 30
    )
    private UserStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        if (createdAt == null) {
            createdAt = now;
        } else {
            createdAt = createdAt.truncatedTo(ChronoUnit.SECONDS);
        }

        updatedAt = createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        if (createdAt != null && now.isBefore(createdAt)) {
            updatedAt = createdAt;
        } else {
            updatedAt = now;
        }
    }
}