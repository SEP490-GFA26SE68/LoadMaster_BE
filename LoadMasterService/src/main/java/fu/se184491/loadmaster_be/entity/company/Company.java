package fu.se184491.loadmaster_be.entity.company;

import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "companies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Company {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_code", length = 50, unique = true, nullable = false)
    private String companyCode;

    @Column(name = "company_name", length = 255, nullable = false)
    private String companyName;

    @Column(name = "tax_code", length = 50, unique = true, nullable = false)
    private String taxCode;

    @Column(name = "billing_email", length = 150, nullable = false)
    private String billingEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private CompanyStatus status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
