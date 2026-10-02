package fu.se184491.loadmaster_be.entity;

import fu.se184491.loadmaster_be.entity.company.Company;


import jakarta.persistence.*;
import lombok.*;


@Entity
@Table(name = "customers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Customer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @Column(name = "contact_phone", length = 20, nullable = false)
    private String contactPhone;

    @Column(name = "address", length = 255, nullable = false)
    private String address;
}
