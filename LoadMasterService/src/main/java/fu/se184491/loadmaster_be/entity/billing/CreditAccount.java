package fu.se184491.loadmaster_be.entity.billing;

import fu.se184491.loadmaster_be.entity.company.Company;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import lombok.*;

@Entity
@Table(name = "credit_accounts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_company_credit_account", columnNames = {"company_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Min(value = 0, message = "Số dư credit không được âm")
    @Column(name = "balance", nullable = false)
    @Builder.Default
    private Integer balance = 0;

    @Version
    @Column(name = "version")
    private Long version;
}
