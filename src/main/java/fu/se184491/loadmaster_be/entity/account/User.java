package fu.se184491.loadmaster_be.entity.account;

import fu.se184491.loadmaster_be.entity.company.Company;


import fu.se184491.loadmaster_be.constant.account.UserStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;


@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "email", length = 150, unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", length = 255, nullable = false)
    private String passwordHash;

    @Column(name = "full_name", length = 150, nullable = false)
    private String fullName;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private UserStatus status;

    @Column(name = "created_at")
    private Instant createdAt;
}
